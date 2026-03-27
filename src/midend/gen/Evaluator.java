package midend.gen;

import ASTNode.*;
import Token.TokenType;
import midend.symbol.Symbol;
import midend.symbol.SymbolManager;
import midend.llvm.constant.*;
import midend.llvm.value.IrGlobalValue;
import midend.llvm.value.IrValue;

import java.util.ArrayList;

public class Evaluator {

    public static int eval(ExpNode expNode) {
        return evalAddExp(expNode.getAddExpNode());
    }

    public static int evalConstExp(ConstExpNode constExpNode) {
        return evalAddExp(constExpNode.getAddExpNode());
    }

    private static int evalAddExp(AddExpNode node) {
        ArrayList<MulExpNode> muls = new ArrayList<>();
        ArrayList<TokenType> ops = new ArrayList<>();
        
        AddExpNode curr = node;
        while (curr != null) {
            muls.add(curr.getMulExpNode());
            if (curr.getAddExpNode() != null) {
                ops.add(curr.getOperator().getTokenType());
            }
            curr = curr.getAddExpNode();
        }
        
        int val = evalMulExp(muls.get(0));
        for (int i = 0; i < ops.size(); i++) {
            int rhs = evalMulExp(muls.get(i + 1));
            if (ops.get(i) == TokenType.PLUS) {
                val += rhs;
            } else {
                val -= rhs;
            }
        }
        return val;
    }

    private static int evalMulExp(MulExpNode node) {
        ArrayList<UnaryExpNode> unarys = new ArrayList<>();
        ArrayList<TokenType> ops = new ArrayList<>();
        
        MulExpNode curr = node;
        while (curr != null) {
            unarys.add(curr.getUnaryExpNode());
            if (curr.getMulExpNode() != null) {
                ops.add(curr.getOperator().getTokenType());
            }
            curr = curr.getMulExpNode();
        }
        
        int val = evalUnaryExp(unarys.get(0));
        for (int i = 0; i < ops.size(); i++) {
            int rhs = evalUnaryExp(unarys.get(i + 1));
            TokenType op = ops.get(i);
            if (op == TokenType.MULT) {
                val *= rhs;
            } else if (op == TokenType.DIV) {
                val /= rhs;
            } else if (op == TokenType.MOD) {
                val %= rhs;
            }
        }
        return val;
    }

    private static int evalUnaryExp(UnaryExpNode node) {
        if (node.getPrimaryExpNode() != null) {
            return evalPrimaryExp(node.getPrimaryExpNode());
        } else if (node.getUnaryOpNode() != null) {
            int val = evalUnaryExp(node.getUnaryExpNode());
            TokenType op = node.getUnaryOpNode().getToken().getTokenType();
            if (op == TokenType.PLUS) return val;
            if (op == TokenType.MINU) return -val;
            if (op == TokenType.NOT) return (val == 0) ? 1 : 0;
        }
        return 0;
    }

    private static int evalPrimaryExp(PrimaryExpNode node) {
        if (node.getExpNode() != null) {
            return eval(node.getExpNode());
        } else if (node.getNumberNode() != null) {
            return Integer.parseInt(node.getNumberNode().getStr());
        } else if (node.getLValNode() != null) {
            return evalLVal(node.getLValNode());
        }
        return 0;
    }

    private static int evalLVal(LValNode node) {
        Symbol symbol = SymbolManager.GetSymbol(node.getIdent().getValue());
        if (symbol == null || symbol.GetIrValue() == null) {
            symbol = SymbolManager.GetSymbolFromFather(node.getIdent().getValue());
        }
        if (symbol == null || symbol.GetIrValue() == null) return 0;
        IrValue irValue = symbol.GetIrValue();
        
        if (irValue instanceof IrGlobalValue) {
             irValue = ((IrGlobalValue) irValue).GetConstant();
        }
        
        ArrayList<Integer> indices = new ArrayList<>();
        for (ExpNode exp : node.getExpNodes()) {
            indices.add(eval(exp));
        }
        
        if (irValue instanceof IrConstant) {
            IrConstant current = (IrConstant) irValue;
            for (int index : indices) {
                if (current instanceof IrConstantArray) {
                    current = ((IrConstantArray) current).GetElement(index);
                } else {
                    return 0;
                }
            }
            
            if (current instanceof IrConstantInt) {
                return ((IrConstantInt) current).GetValue();
            }
        }
        return 0;
    }
}
