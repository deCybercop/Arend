package com.jetbrains.jetpad.vclang.term.pattern.elimtree;

import com.jetbrains.jetpad.vclang.term.Abstract;
import com.jetbrains.jetpad.vclang.term.expr.Substitution;
import com.jetbrains.jetpad.vclang.term.pattern.elimtree.visitor.ElimTreeNodeVisitor;

public class EmptyElimTreeNode extends ElimTreeNode {
  private static final EmptyElimTreeNode myInstance = new EmptyElimTreeNode();
  private EmptyElimTreeNode() {}

  public static EmptyElimTreeNode getInstance() {
    return myInstance;
  }

  @Override
  public <P, R> R accept(ElimTreeNodeVisitor<P, R> visitor, P params) {
    return visitor.visitEmpty(this, params);
  }

  @Override
  public ElimTreeNode matchUntilStuck(Substitution subst, boolean normalize) {
    return this;
  }

  public Abstract.Definition.Arrow getArrow() {
    return null;
  }
}
