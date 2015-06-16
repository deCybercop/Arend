package com.jetbrains.jetpad.vclang.term.definition;

import com.jetbrains.jetpad.vclang.module.ModuleLoader;
import com.jetbrains.jetpad.vclang.term.Abstract;
import com.jetbrains.jetpad.vclang.term.definition.visitor.DefinitionPrettyPrintVisitor;

import java.util.ArrayList;

public abstract class Definition extends Binding implements Abstract.Definition {
  private final Definition myParent;
  private final Precedence myPrecedence;
  private final Fixity myFixity;
  private Universe myUniverse;
  private boolean myHasErrors;

  public Definition(String name, Definition parent, Precedence precedence, Fixity fixity) {
    super(name);
    myParent = parent;
    myPrecedence = precedence;
    myFixity = fixity;
    myUniverse = new Universe.Type(0, Universe.Type.PROP);
    myHasErrors = true;
  }

  public Definition getParent() {
    return myParent;
  }

  @Override
  public Precedence getPrecedence() {
    return myPrecedence;
  }

  @Override
  public Fixity getFixity() {
    return myFixity;
  }

  @Override
  public Universe getUniverse() {
    return myUniverse;
  }

  public void setUniverse(Universe universe) {
    myUniverse = universe;
  }

  public boolean hasErrors() {
    return myHasErrors;
  }

  public void hasErrors(boolean has) {
    myHasErrors = has;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    accept(new DefinitionPrettyPrintVisitor(builder, new ArrayList<String>(), 0), null);
    return builder.toString();
  }

  public String getFullName() {
    return myParent == null || myParent.equals(ModuleLoader.rootModule()) ? getName() : myParent.getFullName() + "." + getName();
  }
}
