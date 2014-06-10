/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.lang.cfg;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.SmartFMap;
import kotlin.Function0;
import kotlin.Function1;
import kotlin.KotlinPackage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.cfg.pseudocode.*;
import org.jetbrains.jet.lang.cfg.pseudocode.instructions.eval.AccessTarget;
import org.jetbrains.jet.lang.cfg.pseudocode.instructions.eval.InstructionWithValue;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.psi.psiUtil.PsiUtilPackage;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.BindingContextUtils;
import org.jetbrains.jet.lang.resolve.BindingTrace;
import org.jetbrains.jet.lang.resolve.CompileTimeConstantUtils;
import org.jetbrains.jet.lang.resolve.calls.model.*;
import org.jetbrains.jet.lang.resolve.constants.CompileTimeConstant;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.lang.resolve.scopes.receivers.ExpressionReceiver;
import org.jetbrains.jet.lang.resolve.scopes.receivers.ReceiverValue;
import org.jetbrains.jet.lang.resolve.scopes.receivers.ThisReceiver;
import org.jetbrains.jet.lang.resolve.scopes.receivers.TransientReceiver;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.expressions.OperatorConventions;
import org.jetbrains.jet.lang.types.lang.KotlinBuiltIns;
import org.jetbrains.jet.lexer.JetToken;
import org.jetbrains.jet.lexer.JetTokens;

import java.util.*;

import static org.jetbrains.jet.lang.cfg.JetControlFlowBuilder.PredefinedOperation.*;
import static org.jetbrains.jet.lang.cfg.JetControlFlowProcessor.CFPContext.IN_CONDITION;
import static org.jetbrains.jet.lang.cfg.JetControlFlowProcessor.CFPContext.NOT_IN_CONDITION;
import static org.jetbrains.jet.lang.diagnostics.Errors.*;
import static org.jetbrains.jet.lexer.JetTokens.*;

public class JetControlFlowProcessor {

    private final JetControlFlowBuilder builder;
    private final BindingTrace trace;

    public JetControlFlowProcessor(BindingTrace trace) {
        this.builder = new JetControlFlowInstructionsGenerator();
        this.trace = trace;
    }

    @NotNull
    public Pseudocode generatePseudocode(@NotNull JetElement subroutine) {
        Pseudocode pseudocode = generate(subroutine);
        ((PseudocodeImpl) pseudocode).postProcess();
        return pseudocode;
    }

    @NotNull
    private Pseudocode generate(@NotNull JetElement subroutine) {
        builder.enterSubroutine(subroutine);
        CFPVisitor cfpVisitor = new CFPVisitor(builder);
        if (subroutine instanceof JetDeclarationWithBody) {
            JetDeclarationWithBody declarationWithBody = (JetDeclarationWithBody) subroutine;
            List<JetParameter> valueParameters = declarationWithBody.getValueParameters();
            for (JetParameter valueParameter : valueParameters) {
                cfpVisitor.generateInstructions(valueParameter, NOT_IN_CONDITION);
            }
            JetExpression bodyExpression = declarationWithBody.getBodyExpression();
            if (bodyExpression != null) {
                cfpVisitor.generateInstructions(bodyExpression, NOT_IN_CONDITION);
            }
        } else {
            cfpVisitor.generateInstructions(subroutine, NOT_IN_CONDITION);
        }
        return builder.exitSubroutine(subroutine);
    }

    private void processLocalDeclaration(@NotNull JetDeclaration subroutine) {
        JetElement parent = PsiTreeUtil.getParentOfType(subroutine, JetElement.class);
        assert parent != null;

        Label afterDeclaration = builder.createUnboundLabel();

        builder.nondeterministicJump(afterDeclaration, parent, null);
        generate(subroutine);
        builder.bindLabel(afterDeclaration);
    }

    /*package*/ enum CFPContext {
        IN_CONDITION(true),
        NOT_IN_CONDITION(false);

        private final boolean inCondition;

        private CFPContext(boolean inCondition) {
            this.inCondition = inCondition;
        }

        public boolean inCondition() {
            return inCondition;
        }
    }

    private class CFPVisitor extends JetVisitorVoidWithParameter<CFPContext> {
        private final JetControlFlowBuilder builder;

        private final JetVisitorVoidWithParameter<CFPContext> conditionVisitor = new JetVisitorVoidWithParameter<CFPContext>() {

            @Override
            public void visitWhenConditionInRangeVoid(@NotNull JetWhenConditionInRange condition, CFPContext context) {
                generateInstructions(condition.getRangeExpression(), context);
                generateInstructions(condition.getOperationReference(), context);

                // TODO : read the call to contains()...
                createNonSyntheticValue(condition, condition.getRangeExpression(), condition.getOperationReference());
            }

            @Override
            public void visitWhenConditionIsPatternVoid(@NotNull JetWhenConditionIsPattern condition, CFPContext context) {
                // TODO: types in CF?
            }

            @Override
            public void visitWhenConditionWithExpressionVoid(@NotNull JetWhenConditionWithExpression condition, CFPContext context) {
                generateInstructions(condition.getExpression(), context);
                copyValue(condition.getExpression(), condition);
            }

            @Override
            public void visitJetElementVoid(@NotNull JetElement element, CFPContext context) {
                throw new UnsupportedOperationException("[JetControlFlowProcessor] " + element.toString());
            }
        };

        private CFPVisitor(@NotNull JetControlFlowBuilder builder) {
            this.builder = builder;
        }

        private void mark(JetElement element) {
            builder.mark(element);
        }

        public void generateInstructions(@Nullable JetElement element, CFPContext context) {
            if (element == null) return;
            element.accept(this, context);
            checkNothingType(element);
        }

        private void checkNothingType(JetElement element) {
            if (!(element instanceof JetExpression)) return;

            JetExpression expression = JetPsiUtil.deparenthesize((JetExpression) element);
            if (expression == null) return;

            if (expression instanceof JetStatementExpression || expression instanceof JetTryExpression
                    || expression instanceof JetIfExpression || expression instanceof JetWhenExpression) {
                return;
            }

            JetType type = trace.getBindingContext().get(BindingContext.EXPRESSION_TYPE, expression);
            if (type != null && KotlinBuiltIns.getInstance().isNothing(type)) {
                builder.jumpToError(expression);
            }
        }

        @NotNull
        private PseudoValue createSyntheticValue(@NotNull JetElement instructionElement, JetElement... from) {
            List<PseudoValue> values = elementsToValues(from.length > 0 ? Arrays.asList(from) : Collections.<JetElement>emptyList());
            return builder.magic(instructionElement, null, values, defaultTypeMap(values), true).getOutputValue();
        }

        @NotNull
        private PseudoValue createNonSyntheticValue(@NotNull JetElement to, @NotNull List<? extends JetElement> from) {
            List<PseudoValue> values = elementsToValues(from);
            return builder.magic(to, to, values, defaultTypeMap(values), false).getOutputValue();
        }

        @NotNull
        private PseudoValue createNonSyntheticValue(@NotNull JetElement to, JetElement... from) {
            return createNonSyntheticValue(to, Arrays.asList(from));
        }

        @NotNull
        private Map<PseudoValue, TypePredicate> defaultTypeMap(List<PseudoValue> values) {
            return PseudocodePackage.expectedTypeFor(AllTypes.instance$, values);
        }

        private void mergeValues(@NotNull List<JetExpression> from, @NotNull JetExpression to) {
            List<PseudoValue> values = elementsToValues(from);
            switch (values.size()) {
                case 0:
                    break;
                case 1:
                    builder.bindValue(values.get(0), to);
                    break;
                default:
                    builder.merge(to, values);
                    break;
            }
        }

        private void copyValue(@Nullable JetElement from, @NotNull JetElement to) {
            PseudoValue value = builder.getBoundValue(from);
            if (value != null) {
                builder.bindValue(value, to);
            }
        }

        private List<PseudoValue> elementsToValues(List<? extends JetElement> from) {
            if (from.isEmpty()) return Collections.emptyList();
            return KotlinPackage.filterNotNull(
                    KotlinPackage.map(
                            from,
                            new Function1<JetElement, PseudoValue>() {
                                @Override
                                public PseudoValue invoke(JetElement element) {
                                    return builder.getBoundValue(element);
                                }
                            }
                    )
            );
        }

        private void generateInitializer(@NotNull JetDeclaration declaration, @NotNull PseudoValue initValue) {
            builder.write(
                    declaration,
                    declaration,
                    initValue,
                    getDeclarationAccessTarget(declaration),
                    Collections.<PseudoValue, ReceiverValue>emptyMap()
            );
        }

        @NotNull
        private AccessTarget getResolvedCallAccessTarget(JetElement element) {
            ResolvedCall<?> resolvedCall = trace.get(BindingContext.RESOLVED_CALL, element);
            return resolvedCall != null ? new AccessTarget.Call(resolvedCall) : AccessTarget.BlackBox.instance$;
        }

        @NotNull
        private AccessTarget getDeclarationAccessTarget(JetElement element) {
            DeclarationDescriptor descriptor = trace.get(BindingContext.DECLARATION_TO_DESCRIPTOR, element);
            return descriptor instanceof VariableDescriptor
                   ? new AccessTarget.Declaration((VariableDescriptor) descriptor)
                   : AccessTarget.BlackBox.instance$;
        }

        @Override
        public void visitParenthesizedExpressionVoid(@NotNull JetParenthesizedExpression expression, CFPContext context) {
            mark(expression);
            JetExpression innerExpression = expression.getExpression();
            if (innerExpression != null) {
                generateInstructions(innerExpression, context);
                copyValue(innerExpression, expression);
            }
        }

        @Override
        public void visitAnnotatedExpressionVoid(@NotNull JetAnnotatedExpression expression, CFPContext context) {
            JetExpression baseExpression = expression.getBaseExpression();
            if (baseExpression != null) {
                generateInstructions(baseExpression, context);
                copyValue(baseExpression, expression);
            }
        }

        @Override
        public void visitThisExpressionVoid(@NotNull JetThisExpression expression, CFPContext context) {
            ResolvedCall<?> resolvedCall = getResolvedCall(expression);
            if (resolvedCall == null) {
                builder.readThis(expression, null);
                return;
            }

            CallableDescriptor resultingDescriptor = resolvedCall.getResultingDescriptor();
            if (resultingDescriptor instanceof ReceiverParameterDescriptor) {
                builder.readThis(expression, (ReceiverParameterDescriptor) resultingDescriptor);
            }

            copyValue(expression, expression.getInstanceReference());
        }

        @Override
        public void visitConstantExpressionVoid(@NotNull JetConstantExpression expression, CFPContext context) {
            CompileTimeConstant<?> constant = trace.get(BindingContext.COMPILE_TIME_VALUE, expression);
            builder.loadConstant(expression, constant);
        }

        @Override
        public void visitSimpleNameExpressionVoid(@NotNull JetSimpleNameExpression expression, CFPContext context) {
            ResolvedCall<?> resolvedCall = getResolvedCall(expression);
            if (resolvedCall instanceof VariableAsFunctionResolvedCall) {
                VariableAsFunctionResolvedCall variableAsFunctionResolvedCall = (VariableAsFunctionResolvedCall) resolvedCall;
                generateCall(expression, expression, variableAsFunctionResolvedCall.getVariableCall());
            }
            else if (!generateCall(expression, expression) && !(expression.getParent() instanceof JetCallExpression)) {
                createNonSyntheticValue(expression, generateAndGetReceiverIfAny(expression));
            }
        }

        @Override
        public void visitLabeledExpressionVoid(@NotNull JetLabeledExpression expression, CFPContext context) {
            mark(expression);
            JetExpression baseExpression = expression.getBaseExpression();
            if (baseExpression != null) {
                generateInstructions(baseExpression, context);
                copyValue(baseExpression, expression);
            }
        }

        @SuppressWarnings("SuspiciousMethodCalls")
        @Override
        public void visitBinaryExpressionVoid(@NotNull JetBinaryExpression expression, CFPContext context) {
            JetSimpleNameExpression operationReference = expression.getOperationReference();
            IElementType operationType = operationReference.getReferencedNameElementType();
            if (!ImmutableSet.of(ANDAND, OROR, EQ, ELVIS).contains(operationType)) {
                mark(expression);
            }

            JetExpression left = expression.getLeft();
            JetExpression right = expression.getRight();
            if (operationType == ANDAND) {
                generateInstructions(left, IN_CONDITION);
                Label resultLabel = builder.createUnboundLabel();
                builder.jumpOnFalse(resultLabel, expression, builder.getBoundValue(left));
                if (right != null) {
                    generateInstructions(right, IN_CONDITION);
                }
                builder.bindLabel(resultLabel);
                if (!context.inCondition()) {
                    predefinedOperation(expression, AND);
                }
            }
            else if (operationType == OROR) {
                generateInstructions(left, IN_CONDITION);
                Label resultLabel = builder.createUnboundLabel();
                builder.jumpOnTrue(resultLabel, expression, builder.getBoundValue(left));
                if (right != null) {
                    generateInstructions(right, IN_CONDITION);
                }
                builder.bindLabel(resultLabel);
                if (!context.inCondition()) {
                    predefinedOperation(expression, OR);
                }
            }
            else if (operationType == EQ) {
                visitAssignment(left, getDeferredValue(right), expression);
            }
            else if (OperatorConventions.ASSIGNMENT_OPERATIONS.containsKey(operationType)) {
                ResolvedCall<?> resolvedCall = getResolvedCall(operationReference);
                if (resolvedCall != null) {
                    CallableDescriptor descriptor = resolvedCall.getResultingDescriptor();
                    Name assignMethodName = OperatorConventions.getNameForOperationSymbol((JetToken) expression.getOperationToken());
                    if (descriptor.getName().equals(assignMethodName)) {
                        generateCall(expression, operationReference, resolvedCall);
                    }
                    else {
                        /* At this point assignment of the form a += b actually means a = a + b
                         * So we first generate call of "+" operation and then use its output pseudo-value
                         * as a right-hand side when generating assignment call
                         */
                        Function0<PseudoValue> rhsDeferredValue =
                                getValueAsFunction(generateCall(null, operationReference, resolvedCall).getOutputValue());
                        visitAssignment(left, rhsDeferredValue, expression);
                    }
                }
                else {
                    generateBothArguments(expression);
                }
            }
            else if (operationType == ELVIS) {
                generateInstructions(left, NOT_IN_CONDITION);
                Label afterElvis = builder.createUnboundLabel();
                builder.jumpOnTrue(afterElvis, expression, builder.getBoundValue(left));
                if (right != null) {
                    generateInstructions(right, NOT_IN_CONDITION);
                }
                builder.bindLabel(afterElvis);
                mergeValues(Arrays.asList(left, right), expression);
            }
            else {
                if (!generateCall(expression, operationReference)) {
                    generateBothArguments(expression);
                }
            }
        }

        private Function0<PseudoValue> getValueAsFunction(final PseudoValue value) {
            return new Function0<PseudoValue>() {
                @Override
                public PseudoValue invoke() {
                    return value;
                }
            };
        }

        private Function0<PseudoValue> getDeferredValue(final JetExpression expression) {
            return new Function0<PseudoValue>() {
                @Override
                public PseudoValue invoke() {
                    generateInstructions(expression, NOT_IN_CONDITION);
                    return builder.getBoundValue(expression);
                }
            };
        }

        private void predefinedOperation(JetBinaryExpression expression, JetControlFlowBuilder.PredefinedOperation operation) {
            builder.predefinedOperation(
                    expression, operation, elementsToValues(Arrays.asList(expression.getLeft(), expression.getRight()))
            );
        }

        private void generateBothArguments(JetBinaryExpression expression) {
            JetExpression left = JetPsiUtil.deparenthesize(expression.getLeft());
            if (left != null) {
                generateInstructions(left, NOT_IN_CONDITION);
            }
            JetExpression right = expression.getRight();
            if (right != null) {
                generateInstructions(right, NOT_IN_CONDITION);
            }

            createNonSyntheticValue(expression, left, right);
        }

        private void visitAssignment(JetExpression lhs, @NotNull Function0<PseudoValue> rhsDeferredValue, JetExpression parentExpression) {
            JetExpression left = JetPsiUtil.deparenthesize(lhs);
            if (left == null) {
                builder.compilationError(lhs, "No lValue in assignment");
                return;
            }

            if (left instanceof JetArrayAccessExpression) {
                generateArrayAssignment((JetArrayAccessExpression) left, rhsDeferredValue, parentExpression);
                return;
            }

            Map<PseudoValue, ReceiverValue> receiverValues = SmartFMap.emptyMap();
            AccessTarget accessTarget = AccessTarget.BlackBox.instance$;
            boolean unsupported = false;
            if (left instanceof JetSimpleNameExpression || left instanceof JetQualifiedExpression) {
                accessTarget = getResolvedCallAccessTarget(PsiUtilPackage.getQualifiedElementSelector(left));
                if (accessTarget instanceof AccessTarget.Call) {
                    receiverValues = getReceiverValues(lhs, ((AccessTarget.Call) accessTarget).getResolvedCall(), true);
                }
            }
            else if (left instanceof JetProperty) {
                accessTarget = getDeclarationAccessTarget(left);
            }
            else {
                unsupported = true;
            }

            PseudoValue rhsValue = rhsDeferredValue.invoke();
            if (unsupported) {
                builder.unsupported(parentExpression); // TODO
            }
            else {
                recordWrite(left, accessTarget, rhsValue, receiverValues, parentExpression);
            }
        }

        private void generateArrayAssignment(
                JetArrayAccessExpression lhs,
                @NotNull Function0<PseudoValue> rhsDeferredValue,
                JetExpression parentExpression
        ) {
            ResolvedCall<FunctionDescriptor> setResolvedCall = trace.get(BindingContext.INDEXED_LVALUE_SET, lhs);

            if (setResolvedCall == null) {
                generateArrayAccess(lhs, null);
                return;
            }

            // In case of simple ('=') array assignment mark instruction is not generated yet, so we put it before generating "set" call
            if (((JetOperationExpression) parentExpression).getOperationReference().getReferencedNameElementType() == EQ) {
                mark(lhs);
            }

            generateInstructions(lhs.getArrayExpression(), NOT_IN_CONDITION);

            Map<PseudoValue, ReceiverValue> receiverValues = getReceiverValues(lhs, setResolvedCall, false);
            SmartFMap<PseudoValue, ValueParameterDescriptor> argumentValues =
                    getArraySetterArguments(rhsDeferredValue, setResolvedCall);

            builder.call(parentExpression, parentExpression, setResolvedCall, receiverValues, argumentValues);
        }

        /* We assume that assignment right-hand side corresponds to the last argument of the call
        *  So receiver instructions/pseudo-values are generated for all arguments except the last one which is replaced
        *  by pre-generated pseudo-value
        *  For example, assignment a[1, 2] += 3 means a.set(1, 2, a.get(1) + 3), so in order to generate "set" call
        *  we first generate instructions for 1 and 2 whereas 3 is replaced by pseudo-value corresponding to "a.get(1) + 3"
        */
        private SmartFMap<PseudoValue, ValueParameterDescriptor> getArraySetterArguments(
                Function0<PseudoValue> rhsDeferredValue,
                final ResolvedCall<FunctionDescriptor> setResolvedCall
        ) {
            List<ValueArgument> valueArguments = KotlinPackage.flatMapTo(
                    setResolvedCall.getResultingDescriptor().getValueParameters(),
                    new ArrayList<ValueArgument>(),
                    new Function1<ValueParameterDescriptor, Iterable<? extends ValueArgument>>() {
                        @Override
                        public Iterable<? extends ValueArgument> invoke(ValueParameterDescriptor descriptor) {
                            ResolvedValueArgument resolvedValueArgument = setResolvedCall.getValueArguments().get(descriptor);
                            return resolvedValueArgument != null
                                   ? resolvedValueArgument.getArguments()
                                   : Collections.<ValueArgument>emptyList();
                        }
                    }
            );

            ValueArgument rhsArgument = KotlinPackage.lastOrNull(valueArguments);
            SmartFMap<PseudoValue, ValueParameterDescriptor> argumentValues = SmartFMap.emptyMap();
            for (ValueArgument valueArgument : valueArguments) {
                ArgumentMapping argumentMapping = setResolvedCall.getArgumentMapping(valueArgument);
                if (argumentMapping.isError() || (!(argumentMapping instanceof ArgumentMatch))) continue;

                ValueParameterDescriptor parameterDescriptor = ((ArgumentMatch) argumentMapping).getValueParameter();
                if (valueArgument != rhsArgument) {
                    argumentValues = generateValueArgument(valueArgument, parameterDescriptor, argumentValues);
                }
                else {
                    PseudoValue rhsValue = rhsDeferredValue.invoke();
                    if (rhsValue != null) {
                        argumentValues = argumentValues.plus(rhsValue, parameterDescriptor);
                    }
                }
            }
            return argumentValues;
        }

        private void recordWrite(
                @NotNull JetExpression left,
                @NotNull AccessTarget target,
                @Nullable PseudoValue rightValue,
                @NotNull Map<PseudoValue, ReceiverValue> receiverValues,
                @NotNull JetExpression parentExpression) {
            VariableDescriptor descriptor = BindingContextUtils.extractVariableDescriptorIfAny(trace.getBindingContext(), left, false);
            if (descriptor != null) {
                builder.write(
                        parentExpression,
                        left,
                        rightValue != null ? rightValue : createSyntheticValue(parentExpression),
                        target,
                        receiverValues
                );
            }
        }

        private void generateArrayAccess(JetArrayAccessExpression arrayAccessExpression, @Nullable ResolvedCall<?> resolvedCall) {
            mark(arrayAccessExpression);
            if (!checkAndGenerateCall(arrayAccessExpression, arrayAccessExpression, resolvedCall)) {
                generateArrayAccessWithoutCall(arrayAccessExpression);
            }
        }

        private void generateArrayAccessWithoutCall(JetArrayAccessExpression arrayAccessExpression) {
            createNonSyntheticValue(arrayAccessExpression, generateArrayAccessArguments(arrayAccessExpression));
        }

        private List<JetExpression> generateArrayAccessArguments(JetArrayAccessExpression arrayAccessExpression) {
            List<JetExpression> inputExpressions = new ArrayList<JetExpression>();

            JetExpression arrayExpression = arrayAccessExpression.getArrayExpression();
            inputExpressions.add(arrayExpression);
            generateInstructions(arrayExpression, NOT_IN_CONDITION);

            for (JetExpression index : arrayAccessExpression.getIndexExpressions()) {
                generateInstructions(index, NOT_IN_CONDITION);
                inputExpressions.add(index);
            }

            return inputExpressions;
        }

        @Override
        public void visitUnaryExpressionVoid(@NotNull JetUnaryExpression expression, CFPContext context) {
            mark(expression);
            JetSimpleNameExpression operationSign = expression.getOperationReference();
            IElementType operationType = operationSign.getReferencedNameElementType();
            JetExpression baseExpression = expression.getBaseExpression();
            if (baseExpression == null) return;
            if (JetTokens.EXCLEXCL == operationType) {
                generateInstructions(baseExpression, NOT_IN_CONDITION);
                builder.predefinedOperation(expression, NOT_NULL_ASSERTION, elementsToValues(Collections.singletonList(baseExpression)));
                return;
            }

            boolean incrementOrDecrement = isIncrementOrDecrement(operationType);
            ResolvedCall<?> resolvedCall = getResolvedCall(operationSign);

            PseudoValue rhsValue;
            if (resolvedCall != null) {
                rhsValue = generateCall(incrementOrDecrement ? null : expression, operationSign, resolvedCall).getOutputValue();
            }
            else {
                generateInstructions(baseExpression, NOT_IN_CONDITION);
                rhsValue = createNonSyntheticValue(expression, baseExpression);
            }

            if (incrementOrDecrement) {
                visitAssignment(baseExpression, getValueAsFunction(rhsValue), expression);
            }
        }

        private boolean isIncrementOrDecrement(IElementType operationType) {
            return operationType == JetTokens.PLUSPLUS || operationType == JetTokens.MINUSMINUS;
        }

        @Override
        public void visitIfExpressionVoid(@NotNull JetIfExpression expression, CFPContext context) {
            mark(expression);
            List<JetExpression> branches = new ArrayList<JetExpression>(2);
            JetExpression condition = expression.getCondition();
            if (condition != null) {
                generateInstructions(condition, IN_CONDITION);
            }
            Label elseLabel = builder.createUnboundLabel();
            builder.jumpOnFalse(elseLabel, expression, builder.getBoundValue(condition));
            JetExpression thenBranch = expression.getThen();
            if (thenBranch != null) {
                branches.add(thenBranch);
                generateInstructions(thenBranch, context);
            }
            else {
                builder.loadUnit(expression);
            }
            Label resultLabel = builder.createUnboundLabel();
            builder.jump(resultLabel, expression);
            builder.bindLabel(elseLabel);
            JetExpression elseBranch = expression.getElse();
            if (elseBranch != null) {
                branches.add(elseBranch);
                generateInstructions(elseBranch, context);
            }
            else {
                builder.loadUnit(expression);
            }
            builder.bindLabel(resultLabel);
            mergeValues(branches, expression);
        }

        private class FinallyBlockGenerator {
            private final JetFinallySection finallyBlock;
            private final CFPContext context;
            private Label startFinally = null;
            private Label finishFinally = null;

            private FinallyBlockGenerator(JetFinallySection block, CFPContext context) {
                finallyBlock = block;
                this.context = context;
            }

            public void generate() {
                JetBlockExpression finalExpression = finallyBlock.getFinalExpression();
                if (finalExpression == null) return;
                if (startFinally != null) {
                    assert finishFinally != null;
                    builder.repeatPseudocode(startFinally, finishFinally);
                    return;
                }
                startFinally = builder.createUnboundLabel("start finally");
                builder.bindLabel(startFinally);
                generateInstructions(finalExpression, context);
                finishFinally = builder.createUnboundLabel("finish finally");
                builder.bindLabel(finishFinally);
            }
        }

        @Override
        public void visitTryExpressionVoid(@NotNull JetTryExpression expression, CFPContext context) {
            mark(expression);

            JetFinallySection finallyBlock = expression.getFinallyBlock();
            final FinallyBlockGenerator finallyBlockGenerator = new FinallyBlockGenerator(finallyBlock, context);
            boolean hasFinally = finallyBlock != null;
            if (hasFinally) {
                builder.enterTryFinally(new GenerationTrigger() {
                    private boolean working = false;

                    @Override
                    public void generate() {
                        // This checks are needed for the case of having e.g. return inside finally: 'try {return} finally{return}'
                        if (working) return;
                        working = true;
                        finallyBlockGenerator.generate();
                        working = false;
                    }
                });
            }

            Label onExceptionToFinallyBlock = generateTryAndCatches(expression, context);

            if (hasFinally) {
                assert onExceptionToFinallyBlock != null : "No finally lable generated: " + expression.getText();

                builder.exitTryFinally();

                Label skipFinallyToErrorBlock = builder.createUnboundLabel("skipFinallyToErrorBlock");
                builder.jump(skipFinallyToErrorBlock, expression);
                builder.bindLabel(onExceptionToFinallyBlock);
                finallyBlockGenerator.generate();
                builder.jumpToError(expression);
                builder.bindLabel(skipFinallyToErrorBlock);

                finallyBlockGenerator.generate();
            }

            List<JetExpression> branches = new ArrayList<JetExpression>();
            branches.add(expression.getTryBlock());
            for (JetCatchClause catchClause : expression.getCatchClauses()) {
                branches.add(catchClause.getCatchBody());
            }
            mergeValues(branches, expression);
        }

        // Returns label for 'finally' block
        @Nullable
        private Label generateTryAndCatches(@NotNull JetTryExpression expression, CFPContext context) {
            List<JetCatchClause> catchClauses = expression.getCatchClauses();
            boolean hasCatches = !catchClauses.isEmpty();

            Label onException = null;
            if (hasCatches) {
                onException = builder.createUnboundLabel("onException");
                builder.nondeterministicJump(onException, expression, null);
            }

            Label onExceptionToFinallyBlock = null;
            if (expression.getFinallyBlock() != null) {
                onExceptionToFinallyBlock = builder.createUnboundLabel("onExceptionToFinallyBlock");
                builder.nondeterministicJump(onExceptionToFinallyBlock, expression, null);
            }

            JetBlockExpression tryBlock = expression.getTryBlock();
            generateInstructions(tryBlock, context);

            if (hasCatches) {
                Label afterCatches = builder.createUnboundLabel("afterCatches");
                builder.jump(afterCatches, expression);

                builder.bindLabel(onException);
                LinkedList<Label> catchLabels = Lists.newLinkedList();
                int catchClausesSize = catchClauses.size();
                for (int i = 0; i < catchClausesSize - 1; i++) {
                    catchLabels.add(builder.createUnboundLabel("catch " + i));
                }
                if (!catchLabels.isEmpty()) {
                    builder.nondeterministicJump(catchLabels, expression);
                }
                boolean isFirst = true;
                for (JetCatchClause catchClause : catchClauses) {
                    builder.enterLexicalScope(catchClause);
                    if (!isFirst) {
                        builder.bindLabel(catchLabels.remove());
                    }
                    else {
                        isFirst = false;
                    }
                    JetParameter catchParameter = catchClause.getCatchParameter();
                    if (catchParameter != null) {
                        builder.declareParameter(catchParameter);
                        generateInitializer(catchParameter, createSyntheticValue(catchParameter));
                    }
                    JetExpression catchBody = catchClause.getCatchBody();
                    if (catchBody != null) {
                        generateInstructions(catchBody, NOT_IN_CONDITION);
                    }
                    builder.jump(afterCatches, expression);
                    builder.exitLexicalScope(catchClause);
                }

                builder.bindLabel(afterCatches);
            }

            return onExceptionToFinallyBlock;
        }

        @Override
        public void visitWhileExpressionVoid(@NotNull JetWhileExpression expression, CFPContext context) {
            mark(expression);
            LoopInfo loopInfo = builder.enterLoop(expression, null, null);

            builder.bindLabel(loopInfo.getConditionEntryPoint());
            JetExpression condition = expression.getCondition();
            if (condition != null) {
                generateInstructions(condition, IN_CONDITION);
            }
            boolean conditionIsTrueConstant = CompileTimeConstantUtils.canBeReducedToBooleanConstant(condition, trace, true);
            if (!conditionIsTrueConstant) {
                builder.jumpOnFalse(loopInfo.getExitPoint(), expression, builder.getBoundValue(condition));
            }

            builder.bindLabel(loopInfo.getBodyEntryPoint());
            JetExpression body = expression.getBody();
            if (body != null) {
                generateInstructions(body, NOT_IN_CONDITION);
            }
            builder.jump(loopInfo.getEntryPoint(), expression);
            builder.exitLoop(expression);
            builder.loadUnit(expression);
        }

        @Override
        public void visitDoWhileExpressionVoid(@NotNull JetDoWhileExpression expression, CFPContext context) {
            builder.enterLexicalScope(expression);
            mark(expression);
            LoopInfo loopInfo = builder.enterLoop(expression, null, null);

            builder.bindLabel(loopInfo.getBodyEntryPoint());
            JetExpression body = expression.getBody();
            if (body != null) {
                generateInstructions(body, NOT_IN_CONDITION);
            }
            builder.bindLabel(loopInfo.getConditionEntryPoint());
            JetExpression condition = expression.getCondition();
            if (condition != null) {
                generateInstructions(condition, IN_CONDITION);
            }
            builder.jumpOnTrue(loopInfo.getEntryPoint(), expression, builder.getBoundValue(condition));
            builder.exitLoop(expression);
            builder.loadUnit(expression);
            builder.exitLexicalScope(expression);
        }

        @Override
        public void visitForExpressionVoid(@NotNull JetForExpression expression, CFPContext context) {
            builder.enterLexicalScope(expression);
            mark(expression);

            JetExpression loopRange = expression.getLoopRange();
            if (loopRange != null) {
                generateInstructions(loopRange, NOT_IN_CONDITION);
            }
            declareLoopParameter(expression);

            // TODO : primitive cases
            Label loopExitPoint = builder.createUnboundLabel();
            Label conditionEntryPoint = builder.createUnboundLabel();

            builder.bindLabel(conditionEntryPoint);
            builder.nondeterministicJump(loopExitPoint, expression, null);

            LoopInfo loopInfo = builder.enterLoop(expression, loopExitPoint, conditionEntryPoint);

            builder.bindLabel(loopInfo.getBodyEntryPoint());
            writeLoopParameterAssignment(expression);

            JetExpression body = expression.getBody();
            if (body != null) {
                generateInstructions(body, NOT_IN_CONDITION);
            }

            builder.nondeterministicJump(loopInfo.getEntryPoint(), expression, null);

            builder.exitLoop(expression);
            builder.loadUnit(expression);
            builder.exitLexicalScope(expression);
        }

        private void declareLoopParameter(JetForExpression expression) {
            JetParameter loopParameter = expression.getLoopParameter();
            JetMultiDeclaration multiDeclaration = expression.getMultiParameter();
            if (loopParameter != null) {
                builder.declareParameter(loopParameter);
            }
            else if (multiDeclaration != null) {
                visitMultiDeclaration(multiDeclaration, false);
            }
        }

        private void writeLoopParameterAssignment(JetForExpression expression) {
            JetParameter loopParameter = expression.getLoopParameter();
            JetMultiDeclaration multiDeclaration = expression.getMultiParameter();
            JetExpression loopRange = expression.getLoopRange();

            JetType loopRangeType = trace.get(BindingContext.EXPRESSION_TYPE, loopRange);
            TypePredicate loopRangeTypeSet = loopRangeType != null ? new SingleType(loopRangeType) : AllTypes.instance$;
            PseudoValue loopRangeValue = builder.getBoundValue(loopRange);

            PseudoValue value = builder.magic(
                    loopRange != null ? loopRange : expression,
                    null,
                    Collections.singletonList(loopRangeValue),
                    Collections.singletonMap(loopRangeValue, loopRangeTypeSet),
                    true
            ).getOutputValue();

            if (loopParameter != null) {
                generateInitializer(loopParameter, value);
            }
            else if (multiDeclaration != null) {
                for (JetMultiDeclarationEntry entry : multiDeclaration.getEntries()) {
                    generateInitializer(entry, value);
                }
            }
        }

        @Override
        public void visitBreakExpressionVoid(@NotNull JetBreakExpression expression, CFPContext context) {
            JetElement loop = getCorrespondingLoop(expression);
            if (loop != null) {
                checkJumpDoesNotCrossFunctionBoundary(expression, loop);
                builder.jump(builder.getExitPoint(loop), expression);
            }
        }

        @Override
        public void visitContinueExpressionVoid(@NotNull JetContinueExpression expression, CFPContext context) {
            JetElement loop = getCorrespondingLoop(expression);
            if (loop != null) {
                checkJumpDoesNotCrossFunctionBoundary(expression, loop);
                builder.jump(builder.getEntryPoint(loop), expression);
            }
        }

        private JetElement getCorrespondingLoop(JetExpressionWithLabel expression) {
            String labelName = expression.getLabelName();
            JetElement loop;
            if (labelName != null) {
                JetSimpleNameExpression targetLabel = expression.getTargetLabel();
                assert targetLabel != null;
                PsiElement labeledElement = trace.get(BindingContext.LABEL_TARGET, targetLabel);
                if (labeledElement instanceof JetLoopExpression) {
                    loop = (JetLoopExpression) labeledElement;
                }
                else {
                    trace.report(NOT_A_LOOP_LABEL.on(expression, targetLabel.getText()));
                    loop = null;
                }
            }
            else {
                loop = builder.getCurrentLoop();
                if (loop == null) {
                    trace.report(BREAK_OR_CONTINUE_OUTSIDE_A_LOOP.on(expression));
                }
            }
            return loop;
        }

        private void checkJumpDoesNotCrossFunctionBoundary(@NotNull JetExpressionWithLabel jumpExpression, @NotNull JetElement jumpTarget) {
            BindingContext bindingContext = trace.getBindingContext();

            FunctionDescriptor labelExprEnclosingFunc = BindingContextUtils.getEnclosingFunctionDescriptor(bindingContext, jumpExpression);
            FunctionDescriptor labelTargetEnclosingFunc = BindingContextUtils.getEnclosingFunctionDescriptor(bindingContext, jumpTarget);
            if (labelExprEnclosingFunc != labelTargetEnclosingFunc) {
                trace.report(BREAK_OR_CONTINUE_JUMPS_ACROSS_FUNCTION_BOUNDARY.on(jumpExpression));
            }
        }

        @Override
        public void visitReturnExpressionVoid(@NotNull JetReturnExpression expression, CFPContext context) {
            JetExpression returnedExpression = expression.getReturnedExpression();
            if (returnedExpression != null) {
                generateInstructions(returnedExpression, NOT_IN_CONDITION);
            }
            JetSimpleNameExpression labelElement = expression.getTargetLabel();
            JetElement subroutine;
            String labelName = expression.getLabelName();
            if (labelElement != null && labelName != null) {
                PsiElement labeledElement = trace.get(BindingContext.LABEL_TARGET, labelElement);
                if (labeledElement != null) {
                    assert labeledElement instanceof JetElement;
                    subroutine = (JetElement) labeledElement;
                }
                else {
                    subroutine = null;
                }
            }
            else {
                subroutine = builder.getReturnSubroutine();
                // TODO : a context check
            }

            if (subroutine instanceof JetFunction || subroutine instanceof JetPropertyAccessor) {
                PseudoValue returnValue = returnedExpression != null ? builder.getBoundValue(returnedExpression) : null;
                if (returnValue == null) {
                    builder.returnNoValue(expression, subroutine);
                }
                else {
                    builder.returnValue(expression, returnValue, subroutine);
                }
            }
        }

        @Override
        public void visitParameterVoid(@NotNull JetParameter parameter, CFPContext context) {
            builder.declareParameter(parameter);
            JetExpression defaultValue = parameter.getDefaultValue();
            if (defaultValue != null) {
                generateInstructions(defaultValue, context);
            }
            generateInitializer(parameter, createSyntheticValue(parameter));
        }

        @Override
        public void visitBlockExpressionVoid(@NotNull JetBlockExpression expression, CFPContext context) {
            boolean declareLexicalScope = !isBlockInDoWhile(expression);
            if (declareLexicalScope) {
                builder.enterLexicalScope(expression);
            }
            mark(expression);
            List<JetElement> statements = expression.getStatements();
            for (JetElement statement : statements) {
                generateInstructions(statement, NOT_IN_CONDITION);
            }
            if (statements.isEmpty()) {
                builder.loadUnit(expression);
            }
            else {
                copyValue(KotlinPackage.lastOrNull(statements), expression);
            }
            if (declareLexicalScope) {
                builder.exitLexicalScope(expression);
            }
        }

        private boolean isBlockInDoWhile(@NotNull JetBlockExpression expression) {
            PsiElement parent = expression.getParent();
            if (parent == null) return false;
            return parent.getParent() instanceof JetDoWhileExpression;
        }

        @Override
        public void visitNamedFunctionVoid(@NotNull JetNamedFunction function, CFPContext context) {
            processLocalDeclaration(function);
        }

        @Override
        public void visitFunctionLiteralExpressionVoid(@NotNull JetFunctionLiteralExpression expression, CFPContext context) {
            mark(expression);
            JetFunctionLiteral functionLiteral = expression.getFunctionLiteral();
            processLocalDeclaration(functionLiteral);
            builder.createFunctionLiteral(expression);
        }

        @Override
        public void visitQualifiedExpressionVoid(@NotNull JetQualifiedExpression expression, CFPContext context) {
            mark(expression);
            JetExpression selectorExpression = expression.getSelectorExpression();
            JetExpression receiverExpression = expression.getReceiverExpression();

            // todo: replace with selectorExpresion != null after parser is fixed
            if (selectorExpression instanceof JetCallExpression || selectorExpression instanceof JetSimpleNameExpression) {
                generateInstructions(selectorExpression, NOT_IN_CONDITION);
                copyValue(selectorExpression, expression);
            }
            else {
                generateInstructions(receiverExpression, NOT_IN_CONDITION);
                createNonSyntheticValue(expression, receiverExpression);
            }
        }

        @Override
        public void visitCallExpressionVoid(@NotNull JetCallExpression expression, CFPContext context) {
            mark(expression);

            JetExpression calleeExpression = expression.getCalleeExpression();
            if (!generateCall(expression, calleeExpression)) {
                List<JetExpression> inputExpressions = new ArrayList<JetExpression>();
                for (ValueArgument argument : expression.getValueArguments()) {
                    JetExpression argumentExpression = argument.getArgumentExpression();
                    if (argumentExpression != null) {
                        generateInstructions(argumentExpression, NOT_IN_CONDITION);
                        inputExpressions.add(argumentExpression);
                    }
                }
                for (JetExpression functionLiteral : expression.getFunctionLiteralArguments()) {
                    generateInstructions(functionLiteral, NOT_IN_CONDITION);
                    inputExpressions.add(functionLiteral);
                }
                generateInstructions(calleeExpression, NOT_IN_CONDITION);
                inputExpressions.add(calleeExpression);
                inputExpressions.add(generateAndGetReceiverIfAny(expression));

                createNonSyntheticValue(expression, inputExpressions);
            }
        }

        @Nullable
        private JetExpression generateAndGetReceiverIfAny(JetExpression expression) {
            PsiElement parent = expression.getParent();
            if (!(parent instanceof JetQualifiedExpression)) return null;

            JetQualifiedExpression qualifiedExpression = (JetQualifiedExpression) parent;
            if (qualifiedExpression.getSelectorExpression() != expression) return null;

            JetExpression receiverExpression = qualifiedExpression.getReceiverExpression();
            generateInstructions(receiverExpression, NOT_IN_CONDITION);

            return receiverExpression;
        }

        @Override
        public void visitPropertyVoid(@NotNull JetProperty property, CFPContext context) {
            builder.declareVariable(property);
            JetExpression initializer = property.getInitializer();
            if (initializer != null) {
                visitAssignment(property, getDeferredValue(initializer), property);
            }
            JetExpression delegate = property.getDelegateExpression();
            if (delegate != null) {
                generateInstructions(delegate, NOT_IN_CONDITION);
            }
            if (JetPsiUtil.isLocal(property)) {
                for (JetPropertyAccessor accessor : property.getAccessors()) {
                    generateInstructions(accessor, NOT_IN_CONDITION);
                }
            }
        }

        @Override
        public void visitMultiDeclarationVoid(@NotNull JetMultiDeclaration declaration, CFPContext context) {
            visitMultiDeclaration(declaration, true);
        }

        private void visitMultiDeclaration(@NotNull JetMultiDeclaration declaration, boolean generateWriteForEntries) {
            JetExpression initializer = declaration.getInitializer();
            generateInstructions(initializer, NOT_IN_CONDITION);
            for (JetMultiDeclarationEntry entry : declaration.getEntries()) {
                builder.declareVariable(entry);

                ResolvedCall<FunctionDescriptor> resolvedCall = trace.get(BindingContext.COMPONENT_RESOLVED_CALL, entry);
                PseudoValue writtenValue = resolvedCall != null
                                           ? builder.call(
                                                entry,
                                                entry,
                                                resolvedCall,
                                                getReceiverValues(initializer, resolvedCall, false),
                                                Collections.<PseudoValue, ValueParameterDescriptor>emptyMap()
                                            ).getOutputValue()
                                           : createSyntheticValue(entry, initializer);
                if (generateWriteForEntries) {
                    generateInitializer(entry, writtenValue != null ? writtenValue : createSyntheticValue(entry));
                }
            }
        }

        @Override
        public void visitPropertyAccessorVoid(@NotNull JetPropertyAccessor accessor, CFPContext context) {
            processLocalDeclaration(accessor);
        }

        @Override
        public void visitBinaryWithTypeRHSExpressionVoid(@NotNull JetBinaryExpressionWithTypeRHS expression, CFPContext context) {
            mark(expression);

            IElementType operationType = expression.getOperationReference().getReferencedNameElementType();
            JetExpression left = expression.getLeft();
            if (operationType == JetTokens.COLON || operationType == JetTokens.AS_KEYWORD || operationType == JetTokens.AS_SAFE) {
                generateInstructions(left, NOT_IN_CONDITION);
                copyValue(left, expression);
            }
            else {
                visitJetElementVoid(expression, context);
                createNonSyntheticValue(expression, left);
            }
        }

        @Override
        public void visitThrowExpressionVoid(@NotNull JetThrowExpression expression, CFPContext context) {
            mark(expression);

            JetExpression thrownExpression = expression.getThrownExpression();
            if (thrownExpression == null) return;

            generateInstructions(thrownExpression, NOT_IN_CONDITION);

            PseudoValue thrownValue = builder.getBoundValue(thrownExpression);
            if (thrownValue == null) return;

            builder.throwException(expression, thrownValue);
        }

        @Override
        public void visitArrayAccessExpressionVoid(@NotNull JetArrayAccessExpression expression, CFPContext context) {
            mark(expression);
            ResolvedCall<FunctionDescriptor> getMethodResolvedCall = trace.get(BindingContext.INDEXED_LVALUE_GET, expression);
            if (!checkAndGenerateCall(expression, expression, getMethodResolvedCall)) {
                generateArrayAccess(expression, getMethodResolvedCall);
            }
        }

        @Override
        public void visitIsExpressionVoid(@NotNull JetIsExpression expression, CFPContext context) {
            mark(expression);
            JetExpression left = expression.getLeftHandSide();
            generateInstructions(left, context);
            createNonSyntheticValue(expression, left);
        }

        @Override
        public void visitWhenExpressionVoid(@NotNull JetWhenExpression expression, CFPContext context) {
            mark(expression);

            JetExpression subjectExpression = expression.getSubjectExpression();
            if (subjectExpression != null) {
                generateInstructions(subjectExpression, context);
            }

            boolean hasElse = false;

            List<JetExpression> branches = new ArrayList<JetExpression>();

            Label doneLabel = builder.createUnboundLabel();

            Label nextLabel = null;
            for (Iterator<JetWhenEntry> iterator = expression.getEntries().iterator(); iterator.hasNext(); ) {
                JetWhenEntry whenEntry = iterator.next();
                mark(whenEntry);

                boolean isElse = whenEntry.isElse();
                if (isElse) {
                    hasElse = true;
                    if (iterator.hasNext()) {
                        trace.report(ELSE_MISPLACED_IN_WHEN.on(whenEntry));
                    }
                }
                Label bodyLabel = builder.createUnboundLabel();

                JetWhenCondition[] conditions = whenEntry.getConditions();
                for (int i = 0; i < conditions.length; i++) {
                    JetWhenCondition condition = conditions[i];
                    condition.accept(conditionVisitor, context);
                    if (i + 1 < conditions.length) {
                        PseudoValue conditionValue = createSyntheticValue(condition, subjectExpression, condition);
                        builder.nondeterministicJump(bodyLabel, expression, conditionValue);
                    }
                }

                if (!isElse) {
                    nextLabel = builder.createUnboundLabel();
                    PseudoValue conditionValue = null;
                    JetWhenCondition lastCondition = KotlinPackage.lastOrNull(conditions);
                    if (lastCondition != null) {
                        conditionValue = createSyntheticValue(lastCondition, subjectExpression, lastCondition);
                    }
                    builder.nondeterministicJump(nextLabel, expression, conditionValue);
                }

                builder.bindLabel(bodyLabel);
                JetExpression whenEntryExpression = whenEntry.getExpression();
                if (whenEntryExpression != null) {
                    generateInstructions(whenEntryExpression, context);
                    branches.add(whenEntryExpression);
                }
                builder.jump(doneLabel, expression);

                if (!isElse) {
                    builder.bindLabel(nextLabel);
                }
            }
            builder.bindLabel(doneLabel);
            if (!hasElse && WhenChecker.mustHaveElse(expression, trace)) {
                trace.report(NO_ELSE_IN_WHEN.on(expression));
            }

            mergeValues(branches, expression);
        }

        @Override
        public void visitObjectLiteralExpressionVoid(@NotNull JetObjectLiteralExpression expression, CFPContext context) {
            mark(expression);
            JetObjectDeclaration declaration = expression.getObjectDeclaration();
            generateInstructions(declaration, context);

            builder.createAnonymousObject(expression);
        }

        @Override
        public void visitObjectDeclarationVoid(@NotNull JetObjectDeclaration objectDeclaration, CFPContext context) {
            visitClassOrObject(objectDeclaration, context);
        }

        @Override
        public void visitStringTemplateExpressionVoid(@NotNull JetStringTemplateExpression expression, CFPContext context) {
            mark(expression);

            List<JetExpression> inputExpressions = new ArrayList<JetExpression>();
            for (JetStringTemplateEntry entry : expression.getEntries()) {
                if (entry instanceof JetStringTemplateEntryWithExpression) {
                    JetExpression entryExpression = entry.getExpression();
                    generateInstructions(entryExpression, NOT_IN_CONDITION);
                    inputExpressions.add(entryExpression);
                }
            }
            builder.loadStringTemplate(expression, elementsToValues(inputExpressions));
        }

        @Override
        public void visitTypeProjectionVoid(@NotNull JetTypeProjection typeProjection, CFPContext context) {
            // TODO : Support Type Arguments. Class object may be initialized at this point");
        }

        @Override
        public void visitAnonymousInitializerVoid(@NotNull JetClassInitializer classInitializer, CFPContext context) {
            generateInstructions(classInitializer.getBody(), context);
        }

        private void visitClassOrObject(JetClassOrObject classOrObject, CFPContext context) {
            for (JetDelegationSpecifier specifier : classOrObject.getDelegationSpecifiers()) {
                generateInstructions(specifier, context);
            }
            List<JetDeclaration> declarations = classOrObject.getDeclarations();
            if (classOrObject.isLocal()) {
                for (JetDeclaration declaration : declarations) {
                    generateInstructions(declaration, context);
                }
                return;
            }
            //For top-level and inner classes and objects functions are collected and checked separately.
            for (JetDeclaration declaration : declarations) {
                if (declaration instanceof JetProperty || declaration instanceof JetClassInitializer) {
                    generateInstructions(declaration, context);
                }
            }
        }

        @Override
        public void visitClassVoid(@NotNull JetClass klass, CFPContext context) {
            List<JetParameter> parameters = klass.getPrimaryConstructorParameters();
            for (JetParameter parameter : parameters) {
                generateInstructions(parameter, context);
            }
            visitClassOrObject(klass, context);
        }

        @Override
        public void visitDelegationToSuperCallSpecifierVoid(@NotNull JetDelegatorToSuperCall call, CFPContext context) {
            List<? extends ValueArgument> valueArguments = call.getValueArguments();
            for (ValueArgument valueArgument : valueArguments) {
                generateInstructions(valueArgument.getArgumentExpression(), context);
            }
        }

        @Override
        public void visitDelegationByExpressionSpecifierVoid(@NotNull JetDelegatorByExpressionSpecifier specifier, CFPContext context) {
            generateInstructions(specifier.getDelegateExpression(), context);
        }

        @Override
        public void visitJetFileVoid(@NotNull JetFile file, CFPContext context) {
            for (JetDeclaration declaration : file.getDeclarations()) {
                if (declaration instanceof JetProperty) {
                    generateInstructions(declaration, context);
                }
            }
        }

        @Override
        public void visitJetElementVoid(@NotNull JetElement element, CFPContext context) {
            builder.unsupported(element);
        }

        @Nullable
        private ResolvedCall<?> getResolvedCall(@NotNull JetElement expression) {
            return trace.get(BindingContext.RESOLVED_CALL, expression);
        }

        private boolean generateCall(JetExpression callExpression, @Nullable JetExpression calleeExpression) {
            if (calleeExpression == null) return false;
            return checkAndGenerateCall(callExpression, calleeExpression, getResolvedCall(calleeExpression));
        }

        private boolean checkAndGenerateCall(JetExpression callExpression, JetExpression calleeExpression, @Nullable ResolvedCall<?> resolvedCall) {
            if (resolvedCall == null) {
                builder.compilationError(calleeExpression, "No resolved call");
                return false;
            }
            generateCall(callExpression, calleeExpression, resolvedCall);
            return true;
        }

        @NotNull
        private InstructionWithValue generateCall(JetExpression callExpression, JetExpression calleeExpression, ResolvedCall<?> resolvedCall) {
            if (resolvedCall instanceof VariableAsFunctionResolvedCall) {
                VariableAsFunctionResolvedCall variableAsFunctionResolvedCall = (VariableAsFunctionResolvedCall) resolvedCall;
                return generateCall(callExpression, calleeExpression, variableAsFunctionResolvedCall.getFunctionCall());
            }

            CallableDescriptor resultingDescriptor = resolvedCall.getResultingDescriptor();
            Map<PseudoValue, ReceiverValue> receivers = getReceiverValues(callExpression, resolvedCall, true);
            SmartFMap<PseudoValue, ValueParameterDescriptor> parameterValues = SmartFMap.emptyMap();
            for (ValueParameterDescriptor parameterDescriptor : resultingDescriptor.getValueParameters()) {
                ResolvedValueArgument argument = resolvedCall.getValueArguments().get(parameterDescriptor);
                if (argument == null) continue;

                parameterValues = generateValueArgument(argument, parameterDescriptor, parameterValues);
            }

            if (resultingDescriptor instanceof VariableDescriptor) {
                assert parameterValues.isEmpty()
                        : "Variable-based call with non-empty argument list: " + resolvedCall.getCall().getCallElement().getText();
                return builder.readVariable(calleeExpression, callExpression, resolvedCall, receivers);
            }
            return builder.call(calleeExpression, callExpression, resolvedCall, receivers, parameterValues);
        }

        @NotNull
        private Map<PseudoValue, ReceiverValue> getReceiverValues(
                JetExpression callExpression,
                ResolvedCall<?> resolvedCall,
                boolean generateInstructions) {
            SmartFMap<PseudoValue, ReceiverValue> receiverValues = SmartFMap.emptyMap();
            receiverValues = getReceiverValues(callExpression, resolvedCall.getThisObject(), generateInstructions, receiverValues);
            receiverValues = getReceiverValues(callExpression, resolvedCall.getReceiverArgument(), generateInstructions, receiverValues);
            return receiverValues;
        }

        @NotNull
        private SmartFMap<PseudoValue, ReceiverValue> getReceiverValues(
                JetExpression callExpression,
                ReceiverValue receiver,
                boolean generateInstructions,
                SmartFMap<PseudoValue, ReceiverValue> receiverValues
        ) {
            if (!receiver.exists()) return receiverValues;

            if (receiver instanceof ThisReceiver) {
                if (generateInstructions) {
                    receiverValues = receiverValues.plus(createSyntheticValue(callExpression), receiver);
                }
            }
            else if (receiver instanceof ExpressionReceiver) {
                JetExpression expression = ((ExpressionReceiver) receiver).getExpression();
                if (generateInstructions) {
                    generateInstructions(expression, NOT_IN_CONDITION);
                }

                PseudoValue receiverPseudoValue = builder.getBoundValue(expression);
                if (receiverPseudoValue != null) {
                    receiverValues = receiverValues.plus(receiverPseudoValue, receiver);
                }
            }
            else if (receiver instanceof TransientReceiver) {
                // Do nothing
            }
            else {
                throw new IllegalArgumentException("Unknown receiver kind: " + receiver);
            }

            return receiverValues;
        }

        @NotNull
        private SmartFMap<PseudoValue, ValueParameterDescriptor> generateValueArgument(
                ResolvedValueArgument argument,
                ValueParameterDescriptor parameterDescriptor,
                SmartFMap<PseudoValue, ValueParameterDescriptor> parameterValues
        ) {
            for (ValueArgument valueArgument : argument.getArguments()) {
                parameterValues = generateValueArgument(valueArgument, parameterDescriptor, parameterValues);
            }

            return parameterValues;
        }

        @NotNull
        private SmartFMap<PseudoValue, ValueParameterDescriptor> generateValueArgument(
                ValueArgument valueArgument,
                ValueParameterDescriptor parameterDescriptor,
                SmartFMap<PseudoValue, ValueParameterDescriptor> parameterValues) {
            JetExpression expression = valueArgument.getArgumentExpression();
            if (expression != null) {
                generateInstructions(expression, NOT_IN_CONDITION);

                PseudoValue argValue = builder.getBoundValue(expression);
                if (argValue != null) {
                    parameterValues = parameterValues.plus(argValue, parameterDescriptor);
                }
            }
            return parameterValues;
        }
    }
}
