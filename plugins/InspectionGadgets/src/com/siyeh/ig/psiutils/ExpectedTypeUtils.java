package com.siyeh.ig.psiutils;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

public class ExpectedTypeUtils{
    /**
     * @noinspection StaticCollection
     */
    private static final Set<IElementType> arithmeticOps = new HashSet<IElementType>(
            5);

    static {
        arithmeticOps.add(JavaTokenType.PLUS);
        arithmeticOps.add(JavaTokenType.MINUS);
        arithmeticOps.add(JavaTokenType.ASTERISK);
        arithmeticOps.add(JavaTokenType.DIV);
        arithmeticOps.add(JavaTokenType.PERC);
    }

    private ExpectedTypeUtils(){
        super();
    }

    public static PsiType findExpectedType(PsiExpression exp){
        PsiElement context = exp.getParent();
        PsiExpression wrappedExp = exp;
        while(context != null && context instanceof PsiParenthesizedExpression){
            wrappedExp = (PsiExpression) context;
            context = context.getParent();
        }
        if(context == null){
            return null;
        }
        final ExpectedTypeVisitor visitor = new ExpectedTypeVisitor(wrappedExp);
        context.accept(visitor);
        return visitor.getExpectedType();
    }

    private static boolean isArithmeticOperation(IElementType sign){
        return arithmeticOps.contains(sign);
    }

    private static boolean isEqualityOperation(IElementType sign){
        return sign.equals(JavaTokenType.EQEQ)
                || sign.equals(JavaTokenType.NE);
    }

    private static int getParameterPosition(PsiExpressionList expressionList,
                                            PsiExpression exp){
        final PsiExpression[] expressions = expressionList.getExpressions();
        for(int i = 0; i < expressions.length; i++){
            if(expressions[i].equals(exp)){
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private static PsiType getTypeOfParemeter(PsiMethod psiMethod,
                               int parameterPosition){
        final PsiParameterList paramList = psiMethod.getParameterList();
        final PsiParameter[] parameters = paramList.getParameters();
        if(parameterPosition < 0){
            return null;
        }
        if(parameterPosition >= parameters.length){
            final int lastParamPosition = parameters.length - 1;
            if(lastParamPosition < 0){
                return null;
            }
            final PsiParameter lastParameter = parameters[lastParamPosition];
            if(lastParameter.isVarArgs()){
                return ((PsiArrayType) lastParameter.getType())
                        .getComponentType();
            }
            return null;
        }

        final PsiParameter param = parameters[parameterPosition];
        if(param.isVarArgs()){
            return ((PsiArrayType) param.getType()).getComponentType();
        }
        return param.getType();
    }

    @Nullable
    private static PsiMethod findCalledMethod(PsiExpressionList expList){
        final PsiElement parent = expList.getParent();
        if(parent instanceof PsiMethodCallExpression){
            final PsiMethodCallExpression methodCall =
                    (PsiMethodCallExpression) parent;
            return methodCall.resolveMethod();
        } else if(parent instanceof PsiNewExpression){
            final PsiNewExpression psiNewExpression = (PsiNewExpression) parent;
            return psiNewExpression.resolveMethod();
        }
        return null;
    }

    private static class ExpectedTypeVisitor extends PsiElementVisitor{
        private final PsiExpression wrappedExp;
        private PsiType expectedType = null;

        ExpectedTypeVisitor(PsiExpression wrappedExp){
            super();
            this.wrappedExp = wrappedExp;
        }

        public PsiType getExpectedType(){
            return expectedType;
        }

        public void visitField(@NotNull PsiField field){
            final PsiExpression initializer = field.getInitializer();
            if(wrappedExp.equals(initializer)){
                expectedType = field.getType();
            }
        }

        public void visitVariable(@NotNull PsiVariable variable){
            expectedType = variable.getType();
        }

        public void visitArrayInitializerExpression(
                PsiArrayInitializerExpression initializer){
            final PsiArrayType arrayType = (PsiArrayType) initializer.getType();
            if(arrayType != null){
                expectedType = arrayType.getComponentType();
            }
        }

        public void visitArrayAccessExpression(
                PsiArrayAccessExpression accessExpression){
            final PsiExpression indexExpression = accessExpression
                    .getIndexExpression();
            if(wrappedExp.equals(indexExpression)){
                expectedType = PsiType.INT;
            }
        }

        public void visitBinaryExpression(
                @NotNull PsiBinaryExpression binaryExp){
            final PsiJavaToken sign = binaryExp.getOperationSign();
            final IElementType tokenType = sign.getTokenType();
            final PsiType type = binaryExp.getType();
            if(TypeUtils.isJavaLangString(type)){
                expectedType = null;
            } else if(isArithmeticOperation(tokenType)){
                expectedType = type;
            } else if(isEqualityOperation(tokenType)){
                final PsiExpression lhs = binaryExp.getLOperand();
                final PsiType lhsType = lhs.getType();
                if(ClassUtils.isPrimitive(lhsType)){
                    expectedType = lhsType;
                    return;
                }
                final PsiExpression rhs = binaryExp.getROperand();
                if(rhs == null){
                    expectedType = null;
                    return;
                }
                final PsiType rhsType = rhs.getType();
                if(ClassUtils.isPrimitive(rhsType)){
                    expectedType = rhsType;
                    return;
                }
                expectedType = null;
            } else{
                expectedType = null;
            }
        }

        public void visitPrefixExpression(
                @NotNull PsiPrefixExpression expression){
            expectedType = expression.getType();
        }

        public void visitPostfixExpression(
                @NotNull PsiPostfixExpression expression){
            expectedType = expression.getType();
        }

        public void visitWhileStatement(
                @NotNull PsiWhileStatement whileStatement){
            expectedType = PsiType.BOOLEAN;
        }

        public void visitForStatement(@NotNull PsiForStatement statement){
            expectedType = PsiType.BOOLEAN;
        }

        public void visitIfStatement(@NotNull PsiIfStatement statement){
            expectedType = PsiType.BOOLEAN;
        }

        public void visitDoWhileStatement(
                @NotNull PsiDoWhileStatement statement){
            expectedType = PsiType.BOOLEAN;
        }

        public void visitSynchronizedStatement(
                @NotNull PsiSynchronizedStatement statement){
            final PsiManager manager = statement.getManager();
            final Project project = manager.getProject();
            final GlobalSearchScope scope = GlobalSearchScope.allScope(project);
            expectedType = PsiType.getJavaLangObject(manager, scope);
        }

        public void visitAssignmentExpression(
                @NotNull PsiAssignmentExpression assignment){
            final PsiExpression rExpression = assignment.getRExpression();
            if(rExpression != null){
                if(rExpression.equals(wrappedExp)){
                    final PsiExpression lExpression =
                            assignment.getLExpression();
                    final PsiType lType = lExpression.getType();
                    if(lType == null){
                        expectedType = null;
                    } else if(TypeUtils.isJavaLangString(lType) &&
                            JavaTokenType.PLUSEQ.equals(
                                    assignment.getOperationSign()
                                            .getTokenType())){
                        // e.g. String += any type
                        expectedType = rExpression.getType();
                    } else{
                        expectedType = lType;
                    }
                }
            }
        }

        public void visitConditionalExpression(
                PsiConditionalExpression conditional){
            final PsiExpression condition = conditional.getCondition();
            if(condition.equals(wrappedExp)){
                expectedType = PsiType.BOOLEAN;
            } else{
                expectedType = conditional.getType();
            }
        }

        public void visitReturnStatement(
                @NotNull PsiReturnStatement returnStatement){
            final PsiMethod method =
                    PsiTreeUtil.getParentOfType(returnStatement,
                                                PsiMethod.class);
            if(method == null){
                expectedType = null;
            } else{
                expectedType = method.getReturnType();
            }
        }

        public void visitDeclarationStatement(
                PsiDeclarationStatement declaration){
            final PsiElement[] declaredElements =
                    declaration.getDeclaredElements();
            for(PsiElement declaredElement1 : declaredElements){
                if(declaredElement1 instanceof PsiVariable){
                    final PsiVariable declaredElement =
                            (PsiVariable) declaredElement1;
                    final PsiExpression initializer =
                            declaredElement.getInitializer();
                    if(wrappedExp.equals(initializer)){
                        expectedType = declaredElement.getType();
                        return;
                    }
                }
            }
        }

        public void visitExpressionList(PsiExpressionList expList){
            final PsiMethod method =
                    ExpectedTypeUtils.findCalledMethod(expList);
            if(method == null){
                expectedType = null;
            } else{
                final int parameterPosition =
                        ExpectedTypeUtils.getParameterPosition(expList,
                                                               wrappedExp);
                expectedType = ExpectedTypeUtils.getTypeOfParemeter(method,
                                                                    parameterPosition);
            }
        }

        public void visitReferenceExpression(
                @NotNull PsiReferenceExpression ref){
            final PsiManager manager = ref.getManager();
            final PsiElement element = ref.resolve();
            if(element instanceof PsiField){
                final PsiField field = (PsiField) element;
                final PsiClass aClass = field.getContainingClass();
                final PsiElementFactory factory = manager.getElementFactory();
                expectedType = factory.createType(aClass);
            } else if(element instanceof PsiMethod){
                final PsiMethod method = (PsiMethod) element;
                final PsiMethod superMethod = method.findDeepestSuperMethod();
                final PsiClass aClass;
                if(superMethod != null){
                    aClass = superMethod.getContainingClass();
                } else{
                    aClass = method.getContainingClass();
                }
                final PsiElementFactory factory = manager.getElementFactory();
                expectedType = factory.createType(aClass);
            } else{
                expectedType = null;
            }
        }
    }
}

