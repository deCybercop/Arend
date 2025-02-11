package org.arend.naming.scope;

import org.arend.naming.reference.GlobalReferable;
import org.arend.naming.reference.Referable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Predicate;

public class ConstructorFilteredScope implements Scope {
  private final Scope myScope;

  public ConstructorFilteredScope(Scope scope) {
    myScope = scope;
  }

  @Nullable
  @Override
  public Referable find(Predicate<Referable> pred) {
    return myScope.find(ref -> ref instanceof GlobalReferable && ((GlobalReferable) ref).getKind() == GlobalReferable.Kind.CONSTRUCTOR && pred.test(ref));
  }

  @Nullable
  @Override
  public Referable resolveName(String name) {
    return myScope.resolveName(name);
  }

  @Nullable
  @Override
  public Scope resolveNamespace(String name, boolean onlyInternal) {
    return myScope.resolveNamespace(name, onlyInternal);
  }

  @Nonnull
  @Override
  public Scope getGlobalSubscopeWithoutOpens() {
    return new ConstructorFilteredScope(myScope.getGlobalSubscopeWithoutOpens());
  }

  @Nullable
  @Override
  public ImportedScope getImportedSubscope() {
    return myScope.getImportedSubscope();
  }
}
