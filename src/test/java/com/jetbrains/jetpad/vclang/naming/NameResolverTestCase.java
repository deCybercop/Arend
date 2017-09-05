package com.jetbrains.jetpad.vclang.naming;

import com.jetbrains.jetpad.vclang.core.context.binding.Binding;
import com.jetbrains.jetpad.vclang.error.DummyErrorReporter;
import com.jetbrains.jetpad.vclang.error.ListErrorReporter;
import com.jetbrains.jetpad.vclang.frontend.ReferenceTypecheckableProvider;
import com.jetbrains.jetpad.vclang.frontend.TextPrettyPrinterInfoProvider;
import com.jetbrains.jetpad.vclang.frontend.namespace.SimpleDynamicNamespaceProvider;
import com.jetbrains.jetpad.vclang.frontend.namespace.SimpleModuleNamespaceProvider;
import com.jetbrains.jetpad.vclang.frontend.namespace.SimpleStaticNamespaceProvider;
import com.jetbrains.jetpad.vclang.frontend.parser.Position;
import com.jetbrains.jetpad.vclang.frontend.reference.GlobalReference;
import com.jetbrains.jetpad.vclang.frontend.storage.PreludeStorage;
import com.jetbrains.jetpad.vclang.naming.namespace.SimpleNamespace;
import com.jetbrains.jetpad.vclang.naming.reference.GlobalReferable;
import com.jetbrains.jetpad.vclang.naming.reference.Referable;
import com.jetbrains.jetpad.vclang.naming.resolving.GroupNameResolver;
import com.jetbrains.jetpad.vclang.naming.resolving.NamespaceProviders;
import com.jetbrains.jetpad.vclang.naming.resolving.visitor.DefinitionResolveNameVisitor;
import com.jetbrains.jetpad.vclang.naming.resolving.visitor.ExpressionResolveNameVisitor;
import com.jetbrains.jetpad.vclang.naming.scope.EmptyScope;
import com.jetbrains.jetpad.vclang.naming.scope.MergeScope;
import com.jetbrains.jetpad.vclang.naming.scope.NamespaceScope;
import com.jetbrains.jetpad.vclang.naming.scope.Scope;
import com.jetbrains.jetpad.vclang.term.Concrete;
import com.jetbrains.jetpad.vclang.term.Group;
import com.jetbrains.jetpad.vclang.term.provider.SourceInfoProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

public abstract class NameResolverTestCase extends ParserTestCase {
  protected final SimpleModuleNamespaceProvider moduleNsProvider  = new SimpleModuleNamespaceProvider();
  protected final SimpleStaticNamespaceProvider staticNsProvider  = new SimpleStaticNamespaceProvider();
  protected final SimpleDynamicNamespaceProvider dynamicNsProvider = new SimpleDynamicNamespaceProvider(ReferenceTypecheckableProvider.INSTANCE);
  private   final NamespaceProviders nsProviders = new NamespaceProviders(moduleNsProvider, staticNsProvider, dynamicNsProvider);
  protected final NameResolver nameResolver = new NameResolver(nsProviders);

  @SuppressWarnings("StaticNonFinalField")
  private static Group LOADED_PRELUDE  = null;
  protected Group prelude = null;
  private Scope globalScope = new EmptyScope();

  protected void loadPrelude() {
    if (prelude != null) throw new IllegalStateException();

    if (LOADED_PRELUDE == null) {
      PreludeStorage preludeStorage = new PreludeStorage(nameResolver);

      ListErrorReporter<Position> internalErrorReporter = new ListErrorReporter<>();
      LOADED_PRELUDE = preludeStorage.loadSource(preludeStorage.preludeSourceId, internalErrorReporter).group;
      assertThat("Failed loading Prelude", internalErrorReporter.getErrorList(), containsErrors(0));
    }

    prelude = LOADED_PRELUDE;

    staticNsProvider.collect(prelude, DummyErrorReporter.INSTANCE);
    globalScope = new NamespaceScope(staticNsProvider.forReferable(prelude.getReferable()));
  }


  private Concrete.Expression<Position> resolveNamesExpr(Scope parentScope, List<Referable> context, String text, int errors) {
    Concrete.Expression<Position> expression = parseExpr(text);
    assertThat(expression, is(notNullValue()));

    expression.accept(new ExpressionResolveNameVisitor<>(parentScope, context, nameResolver, TextPrettyPrinterInfoProvider.INSTANCE, errorReporter), null);
    assertThat(errorList, containsErrors(errors));
    return expression;
  }

  Concrete.Expression<Position> resolveNamesExpr(Scope parentScope, String text, int errors) {
    return resolveNamesExpr(parentScope, new ArrayList<>(), text, errors);
  }

  protected Concrete.Expression resolveNamesExpr(String text, int errors) {
    return resolveNamesExpr(globalScope, new ArrayList<>(), text, errors);
  }

  Concrete.Expression<Position> resolveNamesExpr(Scope parentScope, String text) {
    return resolveNamesExpr(parentScope, text, 0);
  }

  protected Concrete.Expression<Position> resolveNamesExpr(Map<Referable, Binding> context, String text) {
    List<Referable> names = new ArrayList<>(context.keySet());
    return resolveNamesExpr(globalScope, names, text, 0);
  }

  protected Concrete.Expression<Position> resolveNamesExpr(String text) {
    return resolveNamesExpr(new HashMap<>(), text);
  }


  private void resolveNamesDef(Concrete.Definition<Position> definition, int errors) {
    DefinitionResolveNameVisitor<Position> visitor = new DefinitionResolveNameVisitor<>(nameResolver, TextPrettyPrinterInfoProvider.INSTANCE, errorReporter);
    definition.accept(visitor, new MergeScope(globalScope, new NamespaceScope(new SimpleNamespace(definition.getReferable()))));
    assertThat(errorList, containsErrors(errors));
  }

  GlobalReference resolveNamesDef(String text, int errors) {
    GlobalReference result = parseDef(text);
    resolveNamesDef((Concrete.Definition<Position>) result.getDefinition(), errors);
    return result;
  }

  protected GlobalReference resolveNamesDef(String text) {
    return resolveNamesDef(text, 0);
  }


  private void resolveNamesModule(Group group, int errors) {
    GroupNameResolver<Position> groupNameResolver = new GroupNameResolver<>(nameResolver, SourceInfoProvider.TRIVIAL, errorReporter, ReferenceTypecheckableProvider.INSTANCE);
    groupNameResolver.resolveGroup(group, new EmptyScope());
    assertThat(errorList, containsErrors(errors));
  }

  // FIXME[tests] should be package-private
  protected Group resolveNamesModule(String text, int errors) {
    Group group = parseModule(text);
    resolveNamesModule(group, errors);
    return group;
  }

  protected Group resolveNamesModule(String text) {
    return resolveNamesModule(text, 0);
  }


  public GlobalReferable get(GlobalReferable ref, String path) {
    for (String n : path.split("\\.")) {
      GlobalReferable oldRef = ref;

      ref = staticNsProvider.forReferable(oldRef).resolveName(n);
      if (ref != null) continue;

      ref = dynamicNsProvider.forReferable(oldRef).resolveName(n);
      if (ref == null) return null;
    }
    return ref;
  }

}
