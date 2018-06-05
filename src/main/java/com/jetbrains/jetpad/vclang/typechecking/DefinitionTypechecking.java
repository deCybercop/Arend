package com.jetbrains.jetpad.vclang.typechecking;

import com.jetbrains.jetpad.vclang.core.context.LinkList;
import com.jetbrains.jetpad.vclang.core.context.Utils;
import com.jetbrains.jetpad.vclang.core.context.binding.Variable;
import com.jetbrains.jetpad.vclang.core.context.param.*;
import com.jetbrains.jetpad.vclang.core.definition.*;
import com.jetbrains.jetpad.vclang.core.elimtree.Body;
import com.jetbrains.jetpad.vclang.core.elimtree.Clause;
import com.jetbrains.jetpad.vclang.core.elimtree.IntervalElim;
import com.jetbrains.jetpad.vclang.core.elimtree.LeafElimTree;
import com.jetbrains.jetpad.vclang.core.expr.*;
import com.jetbrains.jetpad.vclang.core.expr.type.ExpectedType;
import com.jetbrains.jetpad.vclang.core.expr.type.Type;
import com.jetbrains.jetpad.vclang.core.expr.type.TypeExpression;
import com.jetbrains.jetpad.vclang.core.expr.visitor.NormalizeVisitor;
import com.jetbrains.jetpad.vclang.core.pattern.Pattern;
import com.jetbrains.jetpad.vclang.core.pattern.Patterns;
import com.jetbrains.jetpad.vclang.core.sort.Level;
import com.jetbrains.jetpad.vclang.core.sort.Sort;
import com.jetbrains.jetpad.vclang.error.Error;
import com.jetbrains.jetpad.vclang.error.IncorrectExpressionException;
import com.jetbrains.jetpad.vclang.naming.reference.*;
import com.jetbrains.jetpad.vclang.term.concrete.Concrete;
import com.jetbrains.jetpad.vclang.typechecking.error.LocalErrorReporter;
import com.jetbrains.jetpad.vclang.typechecking.error.LocalErrorReporterCounter;
import com.jetbrains.jetpad.vclang.typechecking.error.local.*;
import com.jetbrains.jetpad.vclang.typechecking.patternmatching.ConditionsChecking;
import com.jetbrains.jetpad.vclang.typechecking.patternmatching.ElimTypechecking;
import com.jetbrains.jetpad.vclang.typechecking.patternmatching.PatternTypechecking;
import com.jetbrains.jetpad.vclang.typechecking.typecheckable.TypecheckingUnit;
import com.jetbrains.jetpad.vclang.typechecking.typeclass.pool.CompositeInstancePool;
import com.jetbrains.jetpad.vclang.typechecking.typeclass.pool.GlobalInstancePool;
import com.jetbrains.jetpad.vclang.typechecking.typeclass.pool.LocalInstancePool;
import com.jetbrains.jetpad.vclang.typechecking.visitor.CheckTypeVisitor;
import com.jetbrains.jetpad.vclang.util.Pair;

import java.util.*;

import static com.jetbrains.jetpad.vclang.core.expr.ExpressionFactory.parameter;
import static com.jetbrains.jetpad.vclang.typechecking.error.local.ArgInferenceError.typeOfFunctionArg;

// TODO[classes]: Make this class into a visitor
class DefinitionTypechecking {
  static Definition typecheckHeader(CheckTypeVisitor visitor, GlobalInstancePool instancePool, Concrete.Definition definition) {
    LocalInstancePool localInstancePool = new LocalInstancePool();
    visitor.setInstancePool(new CompositeInstancePool(localInstancePool, instancePool));
    Definition typechecked = visitor.getTypecheckingState().getTypechecked(definition.getData());

    if (definition instanceof Concrete.FunctionDefinition) {
      FunctionDefinition functionDef = typechecked != null ? (FunctionDefinition) typechecked : new FunctionDefinition(definition.getData());
      try {
        typecheckFunctionHeader(functionDef, (Concrete.FunctionDefinition) definition, visitor, localInstancePool);
      } catch (IncorrectExpressionException e) {
        visitor.getErrorReporter().report(new TypecheckingError(e.getMessage(), definition));
      }
      if (functionDef.getResultType() == null) {
        visitor.getErrorReporter().report(new TypecheckingError("Cannot infer the result type of a recursive function", definition));
        functionDef.setStatus(Definition.TypeCheckingStatus.HEADER_HAS_ERRORS);
      }
      return functionDef;
    } else
    if (definition instanceof Concrete.DataDefinition) {
      DataDefinition dataDef = typechecked != null ? (DataDefinition) typechecked : new DataDefinition(definition.getData());
      try {
        typecheckDataHeader(dataDef, (Concrete.DataDefinition) definition, visitor, localInstancePool);
      } catch (IncorrectExpressionException e) {
        visitor.getErrorReporter().report(new TypecheckingError(e.getMessage(), definition));
      }
      if (dataDef.getSort() == null || dataDef.getSort().getPLevel().isInfinity()) {
        visitor.getErrorReporter().report(new TypecheckingError("Cannot infer the sort of a recursive data type", definition));
        dataDef.setStatus(Definition.TypeCheckingStatus.HEADER_HAS_ERRORS);
      }
      return dataDef;
    } else {
      throw new IllegalStateException();
    }
  }

  static List<Clause> typecheckBody(Definition definition, Concrete.Definition def, CheckTypeVisitor exprVisitor, Set<DataDefinition> dataDefinitions) {
    if (definition instanceof FunctionDefinition) {
      try {
        return typecheckFunctionBody((FunctionDefinition) definition, (Concrete.FunctionDefinition) def, exprVisitor);
      } catch (IncorrectExpressionException e) {
        exprVisitor.getErrorReporter().report(new TypecheckingError(e.getMessage(), def));
      }
    } else
    if (definition instanceof DataDefinition) {
      try {
        if (!typecheckDataBody((DataDefinition) definition, (Concrete.DataDefinition) def, exprVisitor, false, dataDefinitions)) {
          definition.setStatus(Definition.TypeCheckingStatus.BODY_HAS_ERRORS);
        }
      } catch (IncorrectExpressionException e) {
        exprVisitor.getErrorReporter().report(new TypecheckingError(e.getMessage(), def));
      }
    } else {
      throw new IllegalStateException();
    }
    return null;
  }

  static List<Clause> typecheck(TypecheckerState state, GlobalInstancePool instancePool, TypecheckingUnit unit, boolean recursive, LocalErrorReporter errorReporter) {
    CheckTypeVisitor visitor = new CheckTypeVisitor(state, new LinkedHashMap<>(), errorReporter, instancePool);
    if (unit.getDefinition().hasErrors()) {
      visitor.setHasErrors();
    }
    Definition typechecked = state.getTypechecked(unit.getDefinition().getData());

    if (unit.getDefinition() instanceof Concrete.ClassDefinition) {
      ClassDefinition definition = typechecked != null ? (ClassDefinition) typechecked : new ClassDefinition(((Concrete.ClassDefinition) unit.getDefinition()).getData());
      if (typechecked == null) {
        state.record(unit.getDefinition().getData(), definition);
      }
      if (recursive) {
        visitor.getErrorReporter().report(new TypecheckingError("A class cannot be recursive", unit.getDefinition()));
        definition.setStatus(Definition.TypeCheckingStatus.BODY_HAS_ERRORS);

        for (Concrete.ClassField field : ((Concrete.ClassDefinition) unit.getDefinition()).getFields()) {
          ClassField typedDef = new ClassField(field.getData(), definition, new PiExpression(Sort.STD, new TypedSingleDependentLink(false, "this", new ClassCallExpression(definition, Sort.STD)), new ErrorExpression(null, null)));
          typedDef.setStatus(Definition.TypeCheckingStatus.BODY_HAS_ERRORS);
          visitor.getTypecheckingState().record(field.getData(), typedDef);
          definition.addField(typedDef);
          definition.addPersonalField(typedDef);
        }
      } else {
        try {
          typecheckClass((Concrete.ClassDefinition) unit.getDefinition(), definition, visitor);
        } catch (IncorrectExpressionException e) {
          errorReporter.report(new TypecheckingError(e.getMessage(), unit.getDefinition()));
        }
      }
      return null;
    } else if (unit.getDefinition() instanceof Concrete.Instance) {
      FunctionDefinition definition = typechecked != null ? (FunctionDefinition) typechecked : new FunctionDefinition(unit.getDefinition().getData());
      if (typechecked == null) {
        state.record(unit.getDefinition().getData(), definition);
      }
      if (recursive) {
        visitor.getErrorReporter().report(new TypecheckingError("An instance cannot be recursive", unit.getDefinition()));
        definition.setStatus(Definition.TypeCheckingStatus.BODY_HAS_ERRORS);
      } else {
        try {
          typecheckInstance((Concrete.Instance) unit.getDefinition(), definition, visitor);
        } catch (IncorrectExpressionException e) {
          errorReporter.report(new TypecheckingError(e.getMessage(), unit.getDefinition()));
        }
      }
      return null;
    } else if (unit.getDefinition() instanceof Concrete.ClassSynonym) {
      Concrete.ClassSynonym classSyn = (Concrete.ClassSynonym) unit.getDefinition();
      Referable underlyingClass = classSyn.getUnderlyingClass().getReferent();
      if (underlyingClass instanceof ErrorReference) {
        return null;
      }

      ClassDefinition classDef = visitor.referableToDefinition(underlyingClass, ClassDefinition.class, "Expected a class", classSyn.getUnderlyingClass());
      if (typechecked == null && classDef != null) {
        state.record(unit.getDefinition().getData(), classDef);
      }
      if (classDef == null && typechecked instanceof ClassDefinition) {
        classDef = (ClassDefinition) typechecked;
      }
      if (classDef != null) {
        try {
          typecheckClassSynonym(classSyn, classDef, visitor);
        } catch (IncorrectExpressionException e) {
          errorReporter.report(new TypecheckingError(e.getMessage(), unit.getDefinition()));
        }
      }

      return null;
    }

    LocalInstancePool localInstancePool = new LocalInstancePool();
    visitor.setInstancePool(new CompositeInstancePool(localInstancePool, instancePool));

    if (unit.getDefinition() instanceof Concrete.FunctionDefinition) {
      FunctionDefinition definition = typechecked != null ? (FunctionDefinition) typechecked : new FunctionDefinition(unit.getDefinition().getData());
      try {
        typecheckFunctionHeader(definition, (Concrete.FunctionDefinition) unit.getDefinition(), visitor, localInstancePool);
        if (definition.getResultType() == null) {
          definition.setStatus(Definition.TypeCheckingStatus.HEADER_HAS_ERRORS);
          if (recursive) {
            if (((Concrete.FunctionDefinition) unit.getDefinition()).getResultType() == null) {
              errorReporter.report(new TypecheckingError("Cannot infer the result type of a recursive function", unit.getDefinition()));
            }
            return null;
          }
        }
        return typecheckFunctionBody(definition, (Concrete.FunctionDefinition) unit.getDefinition(), visitor);
      } catch (IncorrectExpressionException e) {
        errorReporter.report(new TypecheckingError(e.getMessage(), unit.getDefinition()));
        return null;
      }
    } else
    if (unit.getDefinition() instanceof Concrete.DataDefinition) {
      DataDefinition definition = typechecked != null ? (DataDefinition) typechecked : new DataDefinition(unit.getDefinition().getData());
      try {
        typecheckDataHeader(definition, (Concrete.DataDefinition) unit.getDefinition(), visitor, localInstancePool);
        if (definition.status() == Definition.TypeCheckingStatus.BODY_NEEDS_TYPE_CHECKING) {
          typecheckDataBody(definition, (Concrete.DataDefinition) unit.getDefinition(), visitor, true, Collections.singleton(definition));
        }
      } catch (IncorrectExpressionException e) {
        errorReporter.report(new TypecheckingError(e.getMessage(), unit.getDefinition()));
        return null;
      }
      return null;
    } else {
      throw new IllegalStateException();
    }
  }

  private static Sort typeCheckParameters(List<? extends Concrete.Parameter> parameters, LinkList list, CheckTypeVisitor visitor, LocalInstancePool localInstancePool, Sort expectedSort) {
    Sort sort = Sort.PROP;
    int index = 0;

    for (Concrete.Parameter parameter : parameters) {
      if (parameter instanceof Concrete.TypeParameter) {
        Concrete.TypeParameter typeParameter = (Concrete.TypeParameter) parameter;
        Type paramResult = visitor.finalCheckType(typeParameter.getType(), expectedSort == null ? ExpectedType.OMEGA : new UniverseExpression(expectedSort));
        if (paramResult == null) {
          sort = null;
          paramResult = new TypeExpression(new ErrorExpression(null, null), Sort.SET0);
        } else if (sort != null) {
          sort = sort.max(paramResult.getSortOfType());
        }

        DependentLink param;
        if (parameter instanceof Concrete.TelescopeParameter) {
          List<? extends Referable> referableList = ((Concrete.TelescopeParameter) parameter).getReferableList();
          List<String> names = new ArrayList<>(referableList.size());
          for (Referable referable : referableList) {
            names.add(referable == null ? null : referable.textRepresentation());
          }
          param = parameter(parameter.getExplicit(), names, paramResult);
          index += names.size();

          int i = 0;
          for (DependentLink link = param; link.hasNext(); link = link.getNext(), i++) {
            visitor.getContext().put(referableList.get(i), link);
          }
        } else {
          param = parameter(parameter.getExplicit(), (String) null, paramResult);
          index++;
        }

        /* TODO[classes]
        if (localInstancePool != null) {
          Concrete.ClassView classView = Concrete.getUnderlyingClassView(typeParameter.getType());
          if (classView != null && classView.getClassifyingField() instanceof GlobalReferable) {
            ClassField classifyingField = visitor.referableToClassField(classView.getClassifyingField(), classView);
            if (classifyingField != null) {
              for (DependentLink link = param; link.hasNext(); link = link.getNext()) {
                ReferenceExpression reference = new ReferenceExpression(link);
                Expression oldInstance = localInstancePool.addInstance(FieldCall(classifyingField, reference), classView, reference);
                if (oldInstance != null) {
                  visitor.getErrorReporter().report(new DuplicateInstanceError(oldInstance, reference, parameter));
                }
              }
            }
          }
        }
        */

        list.append(param);
        for (; param.hasNext(); param = param.getNext()) {
          visitor.getFreeBindings().add(param);
        }
      } else {
        visitor.getErrorReporter().report(new ArgInferenceError(typeOfFunctionArg(++index), parameter, new Expression[0]));
        sort = null;
      }
    }

    return sort;
  }

  private static void typecheckFunctionHeader(FunctionDefinition typedDef, Concrete.FunctionDefinition def, CheckTypeVisitor visitor, LocalInstancePool localInstancePool) {
    LinkList list = new LinkList();

    boolean paramsOk = typeCheckParameters(def.getParameters(), list, visitor, localInstancePool, null) != null;
    Expression expectedType = null;
    Concrete.Expression resultType = def.getResultType();
    if (resultType != null) {
      Type expectedTypeResult = def.getBody() instanceof Concrete.ElimFunctionBody ? visitor.finalCheckType(resultType, ExpectedType.OMEGA) : visitor.checkType(resultType, ExpectedType.OMEGA);
      if (expectedTypeResult != null) {
        expectedType = expectedTypeResult.getExpr();
      }
    }

    visitor.getTypecheckingState().record(def.getData(), typedDef);
    typedDef.setParameters(list.getFirst());
    typedDef.setResultType(expectedType);
    typedDef.setStatus(paramsOk ? Definition.TypeCheckingStatus.BODY_NEEDS_TYPE_CHECKING : Definition.TypeCheckingStatus.HEADER_HAS_ERRORS);
  }

  private static List<Clause> typecheckFunctionBody(FunctionDefinition typedDef, Concrete.FunctionDefinition def, CheckTypeVisitor visitor) {
    List<Clause> clauses = null;
    Concrete.FunctionBody body = def.getBody();
    Expression expectedType = typedDef.getResultType();

    if (body instanceof Concrete.ElimFunctionBody) {
      if (expectedType != null) {
        Concrete.ElimFunctionBody elimBody = (Concrete.ElimFunctionBody) body;
        List<DependentLink> elimParams = ElimTypechecking.getEliminatedParameters(elimBody.getEliminatedReferences(), elimBody.getClauses(), typedDef.getParameters(), visitor);
        clauses = new ArrayList<>();
        Body typedBody = elimParams == null ? null : new ElimTypechecking(visitor, expectedType, EnumSet.of(PatternTypechecking.Flag.CHECK_COVERAGE, PatternTypechecking.Flag.CONTEXT_FREE, PatternTypechecking.Flag.ALLOW_INTERVAL, PatternTypechecking.Flag.ALLOW_CONDITIONS)).typecheckElim(elimBody.getClauses(), def, def.getParameters(), typedDef.getParameters(), elimParams, clauses);
        if (typedBody != null) {
          typedDef.setBody(typedBody);
          typedDef.setStatus(Definition.TypeCheckingStatus.NO_ERRORS);
          if (ConditionsChecking.check(typedBody, clauses, typedDef, def, visitor.getErrorReporter())) {
            typedDef.setStatus(Definition.TypeCheckingStatus.NO_ERRORS);
          } else {
            typedDef.setStatus(Definition.TypeCheckingStatus.HAS_ERRORS);
          }
        } else {
          clauses = null;
        }
      } else {
        if (def.getResultType() == null) {
          visitor.getErrorReporter().report(new TypecheckingError("Cannot infer type of the expression", body));
        }
      }
    } else {
      CheckTypeVisitor.Result termResult = visitor.finalCheckExpr(((Concrete.TermFunctionBody) body).getTerm(), expectedType, true);
      if (termResult != null) {
        if (termResult.expression != null) {
          typedDef.setBody(new LeafElimTree(typedDef.getParameters(), termResult.expression));
          clauses = Collections.emptyList();
        }
        typedDef.setResultType(termResult.type);
      }
    }

    typedDef.setStatus(typedDef.getResultType() == null ? Definition.TypeCheckingStatus.HEADER_HAS_ERRORS : typedDef.getBody() == null ? Definition.TypeCheckingStatus.BODY_HAS_ERRORS : visitor.hasErrors() ? Definition.TypeCheckingStatus.HAS_ERRORS : Definition.TypeCheckingStatus.NO_ERRORS);
    return clauses;
  }

  private static void typecheckDataHeader(DataDefinition dataDefinition, Concrete.DataDefinition def, CheckTypeVisitor visitor, LocalInstancePool localInstancePool) {
    LinkList list = new LinkList();

    Sort userSort = null;
    boolean paramsOk = typeCheckParameters(def.getParameters(), list, visitor, localInstancePool, null) != null;

    if (def.getUniverse() != null) {
      Type userTypeResult = visitor.finalCheckType(def.getUniverse(), ExpectedType.OMEGA);
      if (userTypeResult != null) {
        userSort = userTypeResult.getExpr().toSort();
        if (userSort == null) {
          visitor.getErrorReporter().report(new TypecheckingError("Expected a universe", def.getUniverse()));
        }
      }
    }

    dataDefinition.setParameters(list.getFirst());
    dataDefinition.setSort(userSort);
    visitor.getTypecheckingState().record(def.getData(), dataDefinition);

    if (!paramsOk) {
      dataDefinition.setStatus(Definition.TypeCheckingStatus.HEADER_HAS_ERRORS);
      for (Concrete.ConstructorClause clause : def.getConstructorClauses()) {
        for (Concrete.Constructor constructor : clause.getConstructors()) {
          visitor.getTypecheckingState().record(constructor.getData(), new Constructor(constructor.getData(), dataDefinition));
        }
      }
    } else {
      dataDefinition.setStatus(Definition.TypeCheckingStatus.BODY_NEEDS_TYPE_CHECKING);
    }
  }

  private static boolean typecheckDataBody(DataDefinition dataDefinition, Concrete.DataDefinition def, CheckTypeVisitor visitor, boolean polyHLevel, Set<DataDefinition> dataDefinitions) {
    Sort userSort = dataDefinition.getSort();
    Sort inferredSort = Sort.PROP;
    if (userSort != null) {
      if (!userSort.getPLevel().isInfinity()) {
        inferredSort = inferredSort.max(new Sort(userSort.getPLevel(), inferredSort.getHLevel()));
      }
      if (!polyHLevel || !userSort.getHLevel().isInfinity()) {
        inferredSort = inferredSort.max(new Sort(inferredSort.getPLevel(), userSort.getHLevel()));
      }
    }
    dataDefinition.setSort(inferredSort);
    if (def.getConstructorClauses().size() > 1 || !def.getConstructorClauses().isEmpty() && def.getConstructorClauses().get(0).getConstructors().size() > 1) {
      inferredSort = inferredSort.max(Sort.SET0);
    }

    boolean dataOk = true;
    List<DependentLink> elimParams = Collections.emptyList();
    if (def.getEliminatedReferences() != null) {
      elimParams = ElimTypechecking.getEliminatedParameters(def.getEliminatedReferences(), def.getConstructorClauses(), dataDefinition.getParameters(), visitor);
      if (elimParams == null) {
        dataOk = false;
      }
    }

    for (Concrete.ConstructorClause clause : def.getConstructorClauses()) {
      for (Concrete.Constructor constructor : clause.getConstructors()) {
        visitor.getTypecheckingState().record(constructor.getData(), new Constructor(constructor.getData(), dataDefinition));
      }
    }

    LocalErrorReporter errorReporter = visitor.getErrorReporter();
    LocalErrorReporterCounter countingErrorReporter = new LocalErrorReporterCounter(Error.Level.ERROR, errorReporter);
    visitor.setErrorReporter(countingErrorReporter);

    PatternTypechecking dataPatternTypechecking = new PatternTypechecking(visitor.getErrorReporter(), EnumSet.of(PatternTypechecking.Flag.CONTEXT_FREE));
    for (Concrete.ConstructorClause clause : def.getConstructorClauses()) {
      // Typecheck patterns and compute free bindings
      Pair<List<Pattern>, List<Expression>> result = null;
      try (Utils.SetContextSaver<Referable> ignored = new Utils.SetContextSaver<>(visitor.getContext())) {
        if (clause.getPatterns() != null) {
          if (def.getEliminatedReferences() == null) {
            errorReporter.report(new TypecheckingError("Expected a constructor without patterns", clause));
            dataOk = false;
          }
          if (elimParams != null) {
            result = dataPatternTypechecking.typecheckPatterns(clause.getPatterns(), def.getParameters(), dataDefinition.getParameters(), elimParams, def, visitor);
            if (result != null && result.proj2 == null) {
              errorReporter.report(new TypecheckingError("This clause is redundant", clause));
              result = null;
            }
          }
          if (result == null) {
            continue;
          }
        } else {
          if (def.getEliminatedReferences() != null) {
            errorReporter.report(new TypecheckingError("Expected constructors with patterns", clause));
            dataOk = false;
          }
        }

        // Typecheck constructors
        for (Concrete.Constructor constructor : clause.getConstructors()) {
          Patterns patterns = result == null ? null : new Patterns(result.proj1);
          Sort conSort = typecheckConstructor(constructor, patterns, dataDefinition, visitor, dataDefinitions, userSort);
          if (conSort == null) {
            dataOk = false;
            conSort = Sort.PROP;
          }

          inferredSort = inferredSort.max(conSort);
        }
      }
    }
    dataDefinition.setStatus(dataOk ? Definition.TypeCheckingStatus.NO_ERRORS : Definition.TypeCheckingStatus.BODY_HAS_ERRORS);

    visitor.setErrorReporter(errorReporter);

    // Check if constructors pattern match on the interval
    for (Constructor constructor : dataDefinition.getConstructors()) {
      if (constructor.getBody() != null) {
        if (!dataDefinition.matchesOnInterval() && constructor.getBody() instanceof IntervalElim) {
          dataDefinition.setMatchesOnInterval();
          inferredSort = inferredSort.max(new Sort(inferredSort.getPLevel(), Level.INFINITY));
        }
      }
    }

    // Find covariant parameters
    int index = 0;
    for (DependentLink link = dataDefinition.getParameters(); link.hasNext(); link = link.getNext(), index++) {
      boolean isCovariant = true;
      for (Constructor constructor : dataDefinition.getConstructors()) {
        if (!constructor.status().headerIsOK()) {
          continue;
        }
        for (DependentLink link1 = constructor.getParameters(); link1.hasNext(); link1 = link1.getNext()) {
          link1 = link1.getNextTyped(null);
          if (!checkPositiveness(link1.getTypeExpr(), index, null, null, null, Collections.singleton(link))) {
            isCovariant = false;
            break;
          }
        }
        if (!isCovariant) {
          break;
        }
      }
      if (isCovariant) {
        dataDefinition.setCovariant(index);
      }
    }

    // Check truncatedness
    if (def.isTruncated()) {
      if (userSort == null) {
        String msg = "The data type cannot be truncated since its universe is not specified";
        errorReporter.report(new TypecheckingError(Error.Level.WARNING, msg, def));
      } else {
        if (inferredSort.isLessOrEquals(userSort)) {
          String msg = "The data type will not be truncated since it already fits in the specified universe";
          errorReporter.report(new TypecheckingError(Error.Level.WARNING, msg, def.getUniverse()));
        } else {
          dataDefinition.setIsTruncated(true);
        }
      }
    } else if (countingErrorReporter.getErrorsNumber() == 0 && userSort != null && !inferredSort.isLessOrEquals(userSort)) {
      String msg = "Actual universe " + inferredSort + " is not compatible with expected universe " + userSort;
      countingErrorReporter.report(new TypecheckingError(msg, def.getUniverse()));
    }

    dataDefinition.setSort(countingErrorReporter.getErrorsNumber() == 0 && userSort != null ? userSort : inferredSort);
    if (visitor.hasErrors() && dataDefinition.status() == Definition.TypeCheckingStatus.NO_ERRORS) {
      dataDefinition.setStatus(Definition.TypeCheckingStatus.HAS_ERRORS);
    }
    return countingErrorReporter.getErrorsNumber() == 0;
  }

  private static Sort typecheckConstructor(Concrete.Constructor def, Patterns patterns, DataDefinition dataDefinition, CheckTypeVisitor visitor, Set<DataDefinition> dataDefinitions, Sort userSort) {
    Constructor constructor = new Constructor(def.getData(), dataDefinition);
    List<DependentLink> elimParams = null;
    Sort sort;

    try (Utils.SetContextSaver ignore = new Utils.SetContextSaver<>(visitor.getFreeBindings())) {
      try (Utils.SetContextSaver ignored = new Utils.SetContextSaver<>(visitor.getContext())) {
        visitor.getTypecheckingState().record(def.getData(), constructor);
        dataDefinition.addConstructor(constructor);

        LinkList list = new LinkList();
        sort = typeCheckParameters(def.getParameters(), list, visitor, null, userSort);

        int index = 0;
        for (DependentLink link = list.getFirst(); link.hasNext(); link = link.getNext(), index++) {
          link = link.getNextTyped(null);
          if (!checkPositiveness(link.getTypeExpr(), index, def.getParameters(), def, visitor.getErrorReporter(), dataDefinitions)) {
            return null;
          }
        }

        constructor.setParameters(list.getFirst());
        constructor.setPatterns(patterns);

        if (!def.getClauses().isEmpty()) {
          elimParams = ElimTypechecking.getEliminatedParameters(def.getEliminatedReferences(), def.getClauses(), constructor.getParameters(), visitor);
        }
      }
    }

    if (elimParams != null) {
      try (Utils.SetContextSaver ignore = new Utils.SetContextSaver<>(visitor.getFreeBindings())) {
        try (Utils.SetContextSaver ignored = new Utils.SetContextSaver<>(visitor.getContext())) {
          List<Clause> clauses = new ArrayList<>();
          Body body = new ElimTypechecking(visitor, constructor.getDataTypeExpression(Sort.STD), EnumSet.of(PatternTypechecking.Flag.ALLOW_INTERVAL, PatternTypechecking.Flag.ALLOW_CONDITIONS)).typecheckElim(def.getClauses(), def, def.getParameters(), constructor.getParameters(), elimParams, clauses);
          constructor.setBody(body);
          constructor.setClauses(clauses);
          constructor.setStatus(Definition.TypeCheckingStatus.NO_ERRORS);
          ConditionsChecking.check(body, clauses, constructor, def, visitor.getErrorReporter());
        }
      }
    }

    constructor.setStatus(Definition.TypeCheckingStatus.NO_ERRORS);
    return sort;
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private static boolean checkPositiveness(Expression type, int index, List<? extends Concrete.Parameter> parameters, Concrete.Constructor constructor, LocalErrorReporter errorReporter, Set<? extends Variable> variables) {
    List<SingleDependentLink> piParams = new ArrayList<>();
    type = type.getPiParameters(piParams, false);
    for (DependentLink piParam : piParams) {
      if (piParam instanceof UntypedDependentLink) {
        continue;
      }
      if (!checkNonPositiveError(piParam.getTypeExpr(), index, parameters, constructor, errorReporter, variables)) {
        return false;
      }
    }

    DataCallExpression dataCall = type.checkedCast(DataCallExpression.class);
    if (dataCall != null) {
      List<? extends Expression> exprs = dataCall.getDefCallArguments();
      DataDefinition typeDef = dataCall.getDefinition();

      for (int i = 0; i < exprs.size(); i++) {
        if (typeDef.isCovariant(i)) {
          Expression expr = exprs.get(i).normalize(NormalizeVisitor.Mode.WHNF);
          while (expr.isInstance(LamExpression.class)) {
            expr = expr.cast(LamExpression.class).getBody().normalize(NormalizeVisitor.Mode.WHNF);
          }
          if (!checkPositiveness(expr, index, parameters, constructor, errorReporter, variables)) {
            return false;
          }
        } else {
          if (!checkNonPositiveError(exprs.get(i), index, parameters, constructor, errorReporter, variables)) {
            return false;
          }
        }
      }
    } else if (type.isInstance(AppExpression.class)) {
      for (; type.isInstance(AppExpression.class); type = type.cast(AppExpression.class).getFunction()) {
        if (!checkNonPositiveError(type.cast(AppExpression.class).getArgument(), index, parameters, constructor, errorReporter, variables)) {
          return false;
        }
      }
      if (!type.isInstance(ReferenceExpression.class)) {
        //noinspection RedundantIfStatement
        if (!checkNonPositiveError(type, index, parameters, constructor, errorReporter, variables)) {
          return false;
        }
      }
    } else {
      //noinspection RedundantIfStatement
      if (!checkNonPositiveError(type, index, parameters, constructor, errorReporter, variables)) {
        return false;
      }
    }

    return true;
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private static boolean checkNonPositiveError(Expression expr, int index, List<? extends Concrete.Parameter> parameters, Concrete.Constructor constructor, LocalErrorReporter errorReporter, Set<? extends Variable> variables) {
    Variable def = expr.findBinding(variables);
    if (def == null) {
      return true;
    }

    if (errorReporter == null) {
      return false;
    }

    int i = 0;
    Concrete.Parameter parameter = null;
    for (Concrete.Parameter parameter1 : parameters) {
      if (parameter1 instanceof Concrete.TelescopeParameter) {
        i += ((Concrete.TelescopeParameter) parameter1).getReferableList().size();
      } else {
        i++;
      }
      if (i > index) {
        parameter = parameter1;
        break;
      }
    }

    errorReporter.report(new NonPositiveDataError((DataDefinition) def, constructor, parameter == null ? constructor : parameter));
    return false;
  }

  private static void typecheckClass(Concrete.ClassDefinition def, ClassDefinition typedDef, CheckTypeVisitor visitor) {
    LocalErrorReporter errorReporter = visitor.getErrorReporter();
    boolean classOk = true;

    typedDef.setStatus(Definition.TypeCheckingStatus.NO_ERRORS);

    List<GlobalReferable> alreadyImplementFields = new ArrayList<>();
    Concrete.SourceNode alreadyImplementedSourceNode = null;

    // Process super classes
    for (Concrete.ReferenceExpression aSuperClass : def.getSuperClasses()) {
      ClassDefinition superClass = visitor.referableToDefinition(aSuperClass.getReferent(), ClassDefinition.class, "Expected a class", aSuperClass);

      typedDef.addFields(superClass.getFields());
      typedDef.addSuperClass(superClass);

      for (Map.Entry<ClassField, LamExpression> entry : superClass.getImplemented()) {
        if (!implementField(entry.getKey(), entry.getValue(), typedDef, alreadyImplementFields)) {
          classOk = false;
          alreadyImplementedSourceNode = aSuperClass;
        }
      }
    }

    // Process fields
    Concrete.Expression previousType = null;
    ClassField previousField = null;
    for (int i = 0; i < def.getFields().size(); i++) {
      Concrete.ClassField field = def.getFields().get(i);
      if (previousType == field.getResultType()) {
        addField(field.getData(), typedDef, previousField.getType(Sort.STD), visitor.getTypecheckingState()).setStatus(previousField.status());
      } else {
        previousField = typecheckClassField(field, typedDef, visitor);
        previousType = field.getResultType();
      }
    }

    // Process coercing field
    ClassField coercingField = null;
    for (ClassDefinition superClass : typedDef.getSuperClasses()) {
      coercingField = superClass.getCoercingField();
      if (coercingField != null) {
        break;
      }
    }
    if (coercingField == null && def.getCoercingField() != null) {
      Definition definition = visitor.getTypecheckingState().getTypechecked(def.getCoercingField());
      if (definition instanceof ClassField && ((ClassField) definition).getParentClass().equals(typedDef)) {
        coercingField = (ClassField) definition;
      } else {
        errorReporter.report(new TypecheckingError("Internal error: coercing field must be a field belonging to the class", def));
      }
    }
    typedDef.setCoercingField(coercingField);

    // Process implementations
    if (!def.getImplementations().isEmpty()) {
      typedDef.updateSorts();
      for (Concrete.ClassFieldImpl classFieldImpl : def.getImplementations()) {
        ClassField field = visitor.referableToClassField(classFieldImpl.getImplementedField(), classFieldImpl);
        if (field == null) {
          classOk = false;
          continue;
        }
        if (typedDef.isImplemented(field)) {
          classOk = false;
          alreadyImplementFields.add(field.getReferable());
          alreadyImplementedSourceNode = classFieldImpl;
          continue;
        }

        Concrete.LamExpression lamImpl = (Concrete.LamExpression) classFieldImpl.implementation;
        Concrete.TelescopeParameter concreteParameter = (Concrete.TelescopeParameter) lamImpl.getParameters().get(0);
        SingleDependentLink parameter = new TypedSingleDependentLink(false, "this", new ClassCallExpression(typedDef, Sort.STD));
        visitor.getContext().put(concreteParameter.getReferableList().get(0), parameter);
        visitor.getFreeBindings().add(parameter);
        PiExpression fieldType = field.getType(Sort.STD);
        CheckTypeVisitor.Result result = visitor.finalCheckExpr(lamImpl.body, fieldType.getCodomain().subst(fieldType.getParameters(), new ReferenceExpression(parameter)), false);
        if (result == null || result.expression.isInstance(ErrorExpression.class)) {
          classOk = false;
        }

        typedDef.implementField(field, new LamExpression(Sort.STD, parameter, result == null ? new ErrorExpression(null, null) : result.expression));
        visitor.getContext().clear();
        visitor.getFreeBindings().clear();
      }
    }

    if (!alreadyImplementFields.isEmpty()) {
      errorReporter.report(new FieldsImplementationError(true, alreadyImplementFields, alreadyImplementFields.size() > 1 ? def : alreadyImplementedSourceNode));
    }

    typedDef.setStatus(!classOk ? Definition.TypeCheckingStatus.BODY_HAS_ERRORS : visitor.hasErrors() ? Definition.TypeCheckingStatus.HAS_ERRORS : Definition.TypeCheckingStatus.NO_ERRORS);
    typedDef.updateSorts();
  }

  private static PiExpression checkFieldType(Type type, ClassDefinition parentClass) {
    if (!(type instanceof PiExpression)) {
      return null;
    }
    PiExpression piExpr = (PiExpression) type;
    if (piExpr.getParameters().getNext().hasNext()) {
      return null;
    }

    Expression parameterType = piExpr.getParameters().getTypeExpr();
    return parameterType instanceof ClassCallExpression && ((ClassCallExpression) parameterType).getDefinition() == parentClass ? (PiExpression) type : null;
  }

  private static ClassField typecheckClassField(Concrete.ClassField def, ClassDefinition parentClass, CheckTypeVisitor visitor) {
    Type typeResult = visitor.finalCheckType(def.getResultType(), ExpectedType.OMEGA);
    PiExpression piType = checkFieldType(typeResult, parentClass);
    if (piType == null) {
      TypedSingleDependentLink param = new TypedSingleDependentLink(false, "this", new ClassCallExpression(parentClass, Sort.STD));
      if (typeResult == null) {
        piType = new PiExpression(Sort.STD, param, new ErrorExpression(null, null));
      } else {
        visitor.getErrorReporter().report(new TypecheckingError("Internal error: class field must have a function type", def));
        piType = new PiExpression(typeResult.getSortOfType(), param, typeResult.getExpr());
        typeResult = null;
      }
    }

    ClassField typedDef = addField(def.getData(), parentClass, piType, visitor.getTypecheckingState());
    if (typeResult == null) {
      typedDef.setStatus(Definition.TypeCheckingStatus.BODY_HAS_ERRORS);
    }
    return typedDef;
  }

  private static ClassField addField(TCReferable fieldRef, ClassDefinition parentClass, PiExpression piType, TypecheckerState state) {
    ClassField typedDef = new ClassField(fieldRef, parentClass, piType);
    state.record(fieldRef, typedDef);
    parentClass.addField(typedDef);
    parentClass.addPersonalField(typedDef);
    return typedDef;
  }

  private static boolean implementField(ClassField classField, LamExpression implementation, ClassDefinition classDef, List<GlobalReferable> alreadyImplemented) {
    LamExpression oldImpl = classDef.getImplementation(classField);
    if (oldImpl == null) {
      classDef.implementField(classField, implementation);
    }
    if (oldImpl != null && !oldImpl.substArgument(new ReferenceExpression(implementation.getParameters())).equals(implementation.getBody())) {
      alreadyImplemented.add(classField.getReferable());
      return false;
    } else {
      return true;
    }
  }

  private static void typecheckClassSynonym(Concrete.ClassSynonym classSyn, ClassDefinition underlyingClass, CheckTypeVisitor visitor) {
    for (Concrete.ReferenceExpression superClassRef : classSyn.getSuperClasses()) {
      ClassDefinition superClass = visitor.referableToDefinition(superClassRef.getReferent(), ClassDefinition.class, "Expected a class", superClassRef);
      if (superClass != null && !underlyingClass.isSubClassOf(superClass)) {
        visitor.getErrorReporter().report(new SuperClassError(superClassRef.getReferent(), underlyingClass.getReferable(), superClassRef));
      }
    }

    for (Concrete.ClassFieldSynonym fieldSyn : classSyn.getFields()) {
      ClassField underlyingField = visitor.referableToClassField(fieldSyn.getUnderlyingField().getReferent(), fieldSyn.getUnderlyingField());
      if (underlyingField != null) {
        visitor.getTypecheckingState().record(fieldSyn.getData(), underlyingField);
      }
    }

    Deque<TCClassReferable> toVisit = new ArrayDeque<>();
    toVisit.add(classSyn.getData());
    Map<ClassField, GlobalReferable> overridden = new HashMap<>();
    Map<ClassField, Set<GlobalReferable>> errorFields = Collections.emptyMap();
    while (!toVisit.isEmpty()) {
      TCClassReferable classRef = toVisit.pop();
      for (TCReferable fieldRef : classRef.getFieldReferables()) {
        Definition typecheckedDef = visitor.getTypecheckingState().getTypechecked(fieldRef);
        if (typecheckedDef instanceof ClassField) {
          GlobalReferable oldRef = overridden.putIfAbsent((ClassField) typecheckedDef, fieldRef);
          if (oldRef != null) {
            if (errorFields.isEmpty()) {
              errorFields = new LinkedHashMap<>();
            }
            Set<GlobalReferable> list = errorFields.computeIfAbsent((ClassField) typecheckedDef, k -> new LinkedHashSet<>());
            list.add(fieldRef);
            list.add(oldRef);
          }
        }
      }
      toVisit.addAll(classRef.getSuperClassReferences());
    }

    for (Map.Entry<ClassField, Set<GlobalReferable>> entry : errorFields.entrySet()) {
      visitor.getErrorReporter().report(new ClassFieldSynonymError(entry.getKey().getReferable(), entry.getValue(), classSyn));
    }
  }

  private static void typecheckInstance(Concrete.Instance def, FunctionDefinition typedDef, CheckTypeVisitor visitor) {
    LinkList list = new LinkList();
    boolean paramsOk = typeCheckParameters(def.getParameters(), list, visitor, null, null) != null;
    typedDef.setParameters(list.getFirst());
    typedDef.setStatus(Definition.TypeCheckingStatus.HEADER_HAS_ERRORS);
    if (!paramsOk || def.getClassReference().getReferent() instanceof ErrorReference) {
      return;
    }

    CheckTypeVisitor.Result result = visitor.finalCheckExpr(new Concrete.ClassExtExpression(def.getData(), def.getClassReference(), def.getClassFieldImpls()), ExpectedType.OMEGA, false);
    if (result == null || !(result.expression instanceof ClassCallExpression)) {
      return;
    }

    visitor.checkAllImplemented((ClassCallExpression) result.expression, def);
    typedDef.setResultType(result.expression);
    typedDef.setStatus(visitor.hasErrors() ? Definition.TypeCheckingStatus.HAS_ERRORS : Definition.TypeCheckingStatus.NO_ERRORS);
  }
}
