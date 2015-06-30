package com.jetbrains.jetpad.vclang.term.definition.visitor;

import com.jetbrains.jetpad.vclang.term.Abstract;
import com.jetbrains.jetpad.vclang.term.definition.*;
import com.jetbrains.jetpad.vclang.term.error.ArgInferenceError;
import com.jetbrains.jetpad.vclang.term.error.TypeCheckingError;
import com.jetbrains.jetpad.vclang.term.error.TypeMismatchError;
import com.jetbrains.jetpad.vclang.term.expr.Expression;
import com.jetbrains.jetpad.vclang.term.expr.PiExpression;
import com.jetbrains.jetpad.vclang.term.expr.UniverseExpression;
import com.jetbrains.jetpad.vclang.term.expr.arg.Argument;
import com.jetbrains.jetpad.vclang.term.expr.arg.TypeArgument;
import com.jetbrains.jetpad.vclang.term.expr.visitor.CheckTypeVisitor;
import com.jetbrains.jetpad.vclang.term.expr.visitor.FindDefCallVisitor;
import com.jetbrains.jetpad.vclang.term.expr.visitor.NormalizeVisitor;
import com.jetbrains.jetpad.vclang.term.expr.visitor.TerminationCheckVisitor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.jetbrains.jetpad.vclang.term.error.ArgInferenceError.suffix;
import static com.jetbrains.jetpad.vclang.term.error.ArgInferenceError.typeOfFunctionArg;
import static com.jetbrains.jetpad.vclang.term.error.TypeCheckingError.getNames;
import static com.jetbrains.jetpad.vclang.term.expr.ExpressionFactory.*;
import static com.jetbrains.jetpad.vclang.term.expr.arg.Utils.trimToSize;

public class DefinitionCheckTypeVisitor implements AbstractDefinitionVisitor<List<Binding>, Void> {
  private final Definition myResult;
  private final List<TypeCheckingError> myErrors;

  public DefinitionCheckTypeVisitor(Definition result, List<TypeCheckingError> errors) {
    myResult = result;
    myErrors = errors;
  }

  @Override
  public Void visitFunction(Abstract.FunctionDefinition def, List<Binding> localContext) {
    FunctionDefinition functionResult = (FunctionDefinition) myResult;
    FunctionDefinition overriddenFunction = null;
    if (functionResult.isOverridden()) {
      // myErrors.add(new TypeCheckingError("Cannot find function " + def.getName() + " in the parent class", def, getNames(localContext)));
      myErrors.add(new TypeCheckingError("Overridden function cannot be defined in a base class", def, getNames(localContext)));
      return null;
    }

    List<Argument> arguments = new ArrayList<>(def.getArguments().size());
    int origSize = localContext.size();
    Set<Definition> abstractCalls = new HashSet<>();
    CheckTypeVisitor visitor = new CheckTypeVisitor(myResult.getParent(), localContext, abstractCalls, myErrors, CheckTypeVisitor.Side.RHS);

    int index = 1;
    for (Abstract.Argument argument : def.getArguments()) {
      if (argument instanceof Abstract.TypeArgument) {
        if (argument instanceof Abstract.TelescopeArgument) {
          index += ((Abstract.TelescopeArgument) argument).getNames().size();
        } else {
          ++index;
        }

        CheckTypeVisitor.OKResult result = visitor.checkType(((Abstract.TypeArgument) argument).getType(), Universe());
        if (result == null) {
          trimToSize(localContext, origSize);
          return null;
        }

        if (argument instanceof Abstract.TelescopeArgument) {
          List<String> names = ((Abstract.TelescopeArgument) argument).getNames();
          arguments.add(Tele(argument.getExplicit(), names, result.expression));
          for (int i = 0; i < names.size(); ++i) {
            localContext.add(new TypedBinding(names.get(i), result.expression.liftIndex(0, i)));
          }
        } else {
          arguments.add(TypeArg(argument.getExplicit(), result.expression));
          localContext.add(null);
        }
      } else {
        // TODO
        myErrors.add(new ArgInferenceError(typeOfFunctionArg(index), argument, null, new ArgInferenceError.StringPrettyPrintable(def.getName())));
        return null;
      }
    }

    Expression expectedType = null;
    if (def.getResultType() != null) {
      CheckTypeVisitor.OKResult typeResult = visitor.checkType(def.getResultType(), Universe());
      if (typeResult != null) {
        expectedType = typeResult.expression;
      }
    }

    functionResult.setArguments(arguments);
    functionResult.setResultType(expectedType);
    functionResult.typeHasErrors(false);
    functionResult.hasErrors(false);
    visitor.setSide(CheckTypeVisitor.Side.LHS);
    CheckTypeVisitor.OKResult termResult = visitor.checkType(def.getTerm(), expectedType);

    if (termResult != null) {
      functionResult.setTerm(termResult.expression);
      functionResult.setResultType(termResult.type);

      if (!termResult.expression.accept(new TerminationCheckVisitor(functionResult))) {
        myErrors.add(new TypeCheckingError("Termination check failed", def.getTerm(), getNames(localContext)));
        termResult = null;
      }
    } else {
      if (functionResult.getResultType() == null) {
        functionResult.typeHasErrors(true);
      }
    }

    if (termResult == null) {
      functionResult.setTerm(null);
      if (!functionResult.isAbstract()) {
        functionResult.hasErrors(true);
      }
    }
    functionResult.setDependencies(abstractCalls);
    trimToSize(localContext, origSize);

    return null;
  }

  @Override
  public Void visitData(Abstract.DataDefinition def, List<Binding> localContext) {
    DataDefinition dataResult = (DataDefinition) myResult;
    List<TypeArgument> parameters = new ArrayList<>(def.getParameters().size());
    int origSize = localContext.size();
    Universe universe = new Universe.Type(0, Universe.Type.PROP);
    Set<Definition> abstractCalls = new HashSet<>();
    CheckTypeVisitor visitor = new CheckTypeVisitor(myResult.getParent(), localContext, abstractCalls, myErrors, CheckTypeVisitor.Side.RHS);
    for (Abstract.TypeArgument parameter : def.getParameters()) {
      CheckTypeVisitor.OKResult result = visitor.checkType(parameter.getType(), Universe());
      if (result == null) {
        trimToSize(localContext, origSize);
        return null;
      }
      if (parameter instanceof Abstract.TelescopeArgument) {
        parameters.add(Tele(parameter.getExplicit(), ((Abstract.TelescopeArgument) parameter).getNames(), result.expression));
        List<String> names = ((Abstract.TelescopeArgument) parameter).getNames();
        for (int i = 0; i < names.size(); ++i) {
          localContext.add(new TypedBinding(names.get(i), result.expression.liftIndex(0, i)));
        }
      } else {
        parameters.add(TypeArg(parameter.getExplicit(), result.expression));
        localContext.add(new TypedBinding(null, result.expression));
      }
    }

    dataResult.setUniverse(def.getUniverse() != null ? def.getUniverse() : universe);
    dataResult.setParameters(parameters);
    dataResult.hasErrors(false);
    dataResult.setDependencies(abstractCalls);

    constructors_loop:
    for (int i = 0; i < def.getConstructors().size(); ++i) {
      visitConstructor(def.getConstructors().get(i), dataResult.getConstructors().get(i), localContext, abstractCalls);
      if (dataResult.getConstructors().get(i).hasErrors()) {
        continue;
      }

      for (int j = 0; j < dataResult.getConstructors().get(i).getArguments().size(); ++j) {
        Expression type = dataResult.getConstructors().get(i).getArguments().get(j).getType().normalize(NormalizeVisitor.Mode.WHNF);
        while (type instanceof PiExpression) {
          for (TypeArgument argument1 : ((PiExpression) type).getArguments()) {
            if (argument1.getType().accept(new FindDefCallVisitor(dataResult))) {
              String msg = "Non-positive recursive occurrence of data type " + dataResult.getName() + " in constructor " + dataResult.getConstructors().get(i).getName();
              myErrors.add(new TypeCheckingError(msg, def.getConstructors().get(i).getArguments().get(j).getType(), getNames(localContext)));
              continue constructors_loop;
            }
          }
          type = ((PiExpression) type).getCodomain().normalize(NormalizeVisitor.Mode.WHNF);
        }

        List<Expression> exprs = new ArrayList<>();
        type.getFunction(exprs);
        for (Expression expr : exprs) {
          if (expr.accept(new FindDefCallVisitor(dataResult))) {
            String msg = "Non-positive recursive occurrence of data type " + dataResult.getName() + " in constructor " + dataResult.getConstructors().get(i).getName();
            myErrors.add(new TypeCheckingError(msg, def.getConstructors().get(i).getArguments().get(j).getType(), getNames(localContext)));
            continue constructors_loop;
          }
        }
      }

      Universe maxUniverse = universe.max(dataResult.getConstructors().get(i).getUniverse());
      if (maxUniverse == null) {
        String msg = "Universe " + dataResult.getConstructors().get(i).getUniverse() + " of constructor " + dataResult.getConstructors().get(i).getName() + " is not compatible with universe " + universe + " of previous constructors";
        myErrors.add(new TypeCheckingError(msg, null, null));
        continue;
      }
      universe = maxUniverse;
    }

    dataResult.setUniverse(universe);
    trimToSize(localContext, origSize);
    if (def.getUniverse() != null && !universe.lessOrEquals(def.getUniverse())) {
      myErrors.add(new TypeMismatchError(new UniverseExpression(def.getUniverse()), new UniverseExpression(universe), null, new ArrayList<String>()));
    }

    return null;
  }

  private void visitConstructor(Abstract.Constructor def, Constructor constructor, List<Binding> localContext, Set<Definition> abstractCalls) {
    List<TypeArgument> arguments = new ArrayList<>(def.getArguments().size());
    int origSize = localContext.size();
    Universe universe = new Universe.Type(0, Universe.Type.PROP);
    int index = 1;
    String error = null;
    CheckTypeVisitor visitor = new CheckTypeVisitor(constructor.getParent(), localContext, abstractCalls, myErrors, CheckTypeVisitor.Side.RHS);
    for (Abstract.TypeArgument argument : def.getArguments()) {
      CheckTypeVisitor.OKResult result = visitor.checkType(argument.getType(), Universe());
      if (result == null) {
        trimToSize(localContext, origSize);
        return;
      }

      Universe argUniverse = ((UniverseExpression) result.type).getUniverse();
      Universe maxUniverse = universe.max(argUniverse);
      if (maxUniverse == null) {
        error = "Universe " + argUniverse + " of " + index + suffix(index) + " argument is not compatible with universe " + universe + " of previous arguments";
      } else {
        universe = maxUniverse;
      }

      if (argument instanceof Abstract.TelescopeArgument) {
        arguments.add(Tele(argument.getExplicit(), ((Abstract.TelescopeArgument) argument).getNames(), result.expression));
        List<String> names = ((Abstract.TelescopeArgument) argument).getNames();
        for (int i = 0; i < names.size(); ++i) {
          localContext.add(new TypedBinding(names.get(i), result.expression.liftIndex(0, i)));
        }
        index += ((Abstract.TelescopeArgument) argument).getNames().size();
      } else {
        arguments.add(TypeArg(argument.getExplicit(), result.expression));
        localContext.add(new TypedBinding(null, result.expression));
        ++index;
      }
    }

    trimToSize(localContext, origSize);
    constructor.setUniverse(universe);
    constructor.setArguments(arguments);
    constructor.hasErrors(false);
    if (error != null) {
      myErrors.add(new TypeCheckingError(error, DefCall(constructor), new ArrayList<String>()));
    }
  }

  @Override
  public Void visitConstructor(Abstract.Constructor def, List<Binding> localContext) {
    throw new IllegalStateException();
  }

  @Override
  public Void visitClass(Abstract.ClassDefinition def, List<Binding> localContext) {
    TypeCheckingError error = null;
    Universe universe = new Universe.Type(0, Universe.Type.PROP);
    Set<Definition> abstractCalls = new HashSet<>();
    List<Definition> fields = ((ClassDefinition) myResult).getFields();
    for (Definition field : fields) {
      if (field.isAbstract()) {
        Universe maxUniverse = universe.max(field.getUniverse());
        if (maxUniverse == null) {
          String msg = "Universe " + field.getUniverse() + " of field " + field.getName() + " is not compatible with universe " + universe + " of previous fields";
          error = new TypeCheckingError(msg, null, null);
        } else {
          universe = maxUniverse;
        }
      } else {
        if (field.getDependencies() != null) {
          abstractCalls.addAll(field.getDependencies());
        }
      }
    }
    for (Definition field : fields) {
      if (field.isAbstract()) {
        abstractCalls.remove(field);
      }
    }

    if (error != null) {
      myErrors.add(error);
    }
    myResult.setUniverse(universe);
    myResult.hasErrors(false);
    myResult.setDependencies(abstractCalls);
    return null;
  }
}
