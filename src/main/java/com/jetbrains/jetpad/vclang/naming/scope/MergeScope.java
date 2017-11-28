package com.jetbrains.jetpad.vclang.naming.scope;

import com.jetbrains.jetpad.vclang.naming.reference.Referable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;

public class MergeScope implements Scope {
  private final Collection<Scope> myScopes;

  public MergeScope(Collection<Scope> scopes) {
    myScopes = scopes;
  }

  public MergeScope(Scope... scopes) {
    myScopes = Arrays.asList(scopes);
  }

  @Nonnull
  @Override
  public List<Referable> getElements() {
    List<Referable> result = new ArrayList<>();
    for (Scope scope : myScopes) {
      result.addAll(scope.getElements());
    }
    return result;
  }

  @Override
  public Referable resolveName(String name) {
    for (Scope scope : myScopes) {
      Referable ref = scope.resolveName(name);
      if (ref != null) {
        return ref;
      }
    }
    return null;
  }

  @Nullable
  @Override
  public Scope resolveNamespace(String name, boolean resolveModuleNames) {
    for (Scope scope : myScopes) {
      Scope result = scope.resolveNamespace(name, resolveModuleNames);
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  @Override
  public Referable find(Predicate<Referable> pred) {
    for (Scope scope : myScopes) {
      Referable ref = scope.find(pred);
      if (ref != null) {
        return ref;
      }
    }
    return null;
  }

  @Nullable
  @Override
  public ImportedScope getImportedSubscope() {
    for (Scope scope : myScopes) {
      ImportedScope importedScope = scope.getImportedSubscope();
      if (importedScope != null) {
        return importedScope;
      }
    }
    return null;
  }
}
