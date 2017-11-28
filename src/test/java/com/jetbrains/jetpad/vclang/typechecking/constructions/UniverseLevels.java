package com.jetbrains.jetpad.vclang.typechecking.constructions;


import com.jetbrains.jetpad.vclang.core.expr.UniverseExpression;
import com.jetbrains.jetpad.vclang.core.sort.Sort;
import com.jetbrains.jetpad.vclang.typechecking.TypeCheckingTestCase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UniverseLevels extends TypeCheckingTestCase {
  @Test
  public void dataExpansion() {
    typeCheckModule(
      "\\data D (A : \\Type) (a : A) | d (B : A -> \\Type2)\n" +
      "\\function f : \\Pi {A : \\Type} {a : A} -> (A -> \\Type1) -> D A a => \\lam B => d B\n" +
      "\\function test => f {\\Set0} {\\Prop} (\\lam _ => \\Type0)");
  }

  @Test
  public void allowedInArgs() {
    typeCheckModule("\\function f (A : \\Type -> \\Type) => 0");
  }

  @Test
  public void allowedInResultType() {
    typeCheckModule("\\function g : \\Type -> \\Type => \\lam X => X");
  }

  @Test
  public void allowedAsExpression() {
    typeCheckModule("\\function f => \\Type");
  }

  @Test
  public void equalityOfTypes() {
    typeCheckModule("\\function f (A B : \\Type) => A = B");
  }

  @Test
  public void callPolyFromOmega() {
     typeCheckModule(
         "\\function f (A : \\Type) => A\n" +
         "\\function g (A : \\Type) => f A");
  }

  @Test
  public void typeOmegaResult() {
    typeCheckModule("\\function f (A : \\Type) : \\Type => A");
  }

  @Test
  public void callNonPolyFromOmega() {
    typeCheckModule(
        "\\function f (A : \\Type0) => 0\n" +
        "\\function g (A : \\Type) => f A", 1);
  }

  @Test
  public void levelP() {
    typeCheckExpr("\\Type \\lh", null, 1);
  }

  @Test
  public void levelH() {
    typeCheckExpr("\\Type1 \\lp", null, 1);
  }

  @Test
  public void truncatedLevel() {
    assertEquals(new UniverseExpression(new Sort(7, 2)), typeCheckExpr("\\2-Type 7", null).expression);
  }

  @Test
  public void functionMaxTest() {
    typeCheckModule(
      "\\data Foo (A : \\Type) : \\Type | foo A\n" +
      "\\function bar (A : \\Type \\lp (\\max \\lh 1)) : \\Type \\lp (\\max \\lh 1) => Foo A");
  }

  @Test
  public void dataMaxTest() {
    typeCheckModule(
      "\\data Foo (A : \\Type) : \\Type | foo A\n" +
      "\\data Bar (A : \\Type \\lp (\\max \\lh 1)) : \\Type \\lp (\\max \\lh 1) | bar (Foo A)");
  }
}
