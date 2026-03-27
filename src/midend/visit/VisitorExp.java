package midend.visit;

import ASTNode.*;
import Token.TokenType;
import midend.llvm.IrBuilder;
import midend.llvm.constant.IrConstantInt;
import midend.llvm.instr.*;
import midend.llvm.instr.io.GetIntInstr;
import midend.llvm.instr.io.PrintCharInstr;
import midend.llvm.instr.io.PrintIntInstr;
import midend.llvm.type.IrArrayType;
import midend.llvm.type.IrBaseType;
import midend.llvm.type.IrPointerType;
import midend.llvm.type.IrType;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrValue;
import midend.symbol.Symbol;
import midend.symbol.SymbolManager;
import midend.symbol.SymbolType;

import java.util.ArrayList;

public class VisitorExp {

    public static IrValue VisitExp(ExpNode node) {
        return VisitAddExp(node.getAddExpNode());
    }

    private static IrValue VisitAddExp(AddExpNode node) {
        AddExpNode currNode = node;
        ArrayList<IrValue> values = new ArrayList<>();
        ArrayList<TokenType> operators = new ArrayList<>();
        
        values.add(VisitMulExp(currNode.getMulExpNode()));
        
        while (currNode.getAddExpNode() != null) {
            operators.add(currNode.getOperator().getTokenType());
            currNode = currNode.getAddExpNode();
            values.add(VisitMulExp(currNode.getMulExpNode()));
        }
        
        IrValue result = values.get(0);
        for (int i = 0; i < operators.size(); i++) {
            IrValue rhs = values.get(i + 1);
            String op = (operators.get(i) == TokenType.PLUS) ? "+" : "-";
            Instr instr = new AluInstr(op, result, rhs);
            result = instr;
        }
        return result;
    }

    private static IrValue VisitMulExp(MulExpNode node) {
        MulExpNode currNode = node;
        ArrayList<IrValue> values = new ArrayList<>();
        ArrayList<TokenType> operators = new ArrayList<>();
        
        values.add(VisitUnaryExp(currNode.getUnaryExpNode()));
        
        while (currNode.getMulExpNode() != null) {
            operators.add(currNode.getOperator().getTokenType());
            currNode = currNode.getMulExpNode();
            values.add(VisitUnaryExp(currNode.getUnaryExpNode()));
        }
        
        IrValue result = values.get(0);
        for (int i = 0; i < operators.size(); i++) {
            IrValue rhs = values.get(i + 1);
            TokenType tokenType = operators.get(i);
            String op;
            if (tokenType == TokenType.MULT) op = "*";
            else if (tokenType == TokenType.DIV) op = "/";
            else op = "%";
            
            Instr instr = new AluInstr(op, result, rhs);
            result = instr;
        }
        return result;
    }

    private static IrValue VisitUnaryExp(UnaryExpNode node) {
        if (node.getPrimaryExpNode() != null) {
            return VisitPrimaryExp(node.getPrimaryExpNode());
        } else if (node.getUnaryOpNode() != null) {
            IrValue val = VisitUnaryExp(node.getUnaryExpNode());
            TokenType op = node.getUnaryOpNode().getToken().getTokenType();
            if (op == TokenType.PLUS) return val;
            if (op == TokenType.MINU) {
                Instr instr = new AluInstr("-", new IrConstantInt(0), val);
                return instr;
            }
            if (op == TokenType.NOT) {
                Instr cmp = new CompareInstr("==", val, new IrConstantInt(0));
                Instr zext = new ExtendInstr(cmp, IrBaseType.INT32);
                return zext;
            }
        } else if (node.getIdent() != null) {
            return VisitFuncCall(node);
        }
        return null;
    }
    
    private static IrValue VisitFuncCall(UnaryExpNode node) {
        String name = node.getIdent().getValue();
        ArrayList<IrValue> args = new ArrayList<>();
        if (node.getFuncRParamsNode() != null) {
            for (ExpNode e : node.getFuncRParamsNode().getExpNodes()) {
                args.add(VisitExp(e));
            }
        }
        
        if (name.equals("getint")) {
            Instr call = new GetIntInstr();
            return call;
        }
        if (name.equals("putint")) {
            new PrintIntInstr(args.get(0));
            return null; 
        }
        if (name.equals("putch")) {
            new PrintCharInstr(args.get(0));
            return null;
        }
        
        Symbol sym = SymbolManager.GetSymbol(name);
        if (sym == null || sym.GetIrValue() == null) {
            sym = SymbolManager.GetSymbolFromFather(name);
        }
        if (sym == null) {
            throw new RuntimeException("Undefined function in IR generation: " + name);
        }
        IrFunction func;
        if (sym.GetIrValue() instanceof IrFunction) {
            func = (IrFunction) sym.GetIrValue();
        } else {
            IrType retType = sym.GetSymbolType() == SymbolType.VOID_FUNC ? IrBaseType.VOID : IrBaseType.INT32;
            func = new IrFunction(IrBuilder.GetFuncName(name), retType);
            sym.SetIrValue(func);
        }
        Instr call = new CallInstr(func, args);
        return call;
    }

    private static IrValue VisitPrimaryExp(PrimaryExpNode node) {
        if (node.getExpNode() != null) {
            return VisitExp(node.getExpNode());
        } else if (node.getNumberNode() != null) {
            return new IrConstantInt(Integer.parseInt(node.getNumberNode().getStr()));
        } else if (node.getLValNode() != null) {
            IrValue addr = VisitorLVal.VisitLVal(node.getLValNode());
            
            if (addr.GetIrType() instanceof IrPointerType) {
                IrType target = ((IrPointerType) addr.GetIrType()).GetTargetType();
                if (target.IsArrayType()) {
                    GepInstr gep = new GepInstr(addr, new IrConstantInt(0)); 
                    return gep;
                }
                LoadInstr load = new LoadInstr(addr);
                return load;
            }
            return addr;
        }
        return null;
    }

    public static void VisitCond(CondNode node, IrBasicBlock trueTarget, IrBasicBlock falseTarget) {
        VisitLOrExp(node.getLOrExpNode(), trueTarget, falseTarget);
    }
    
    private static void VisitLOrExp(LOrExpNode node, IrBasicBlock trueTarget, IrBasicBlock falseTarget) {
        ArrayList<LAndExpNode> lands = new ArrayList<>();
        LOrExpNode curr = node;
        while (curr.getLOrExpNode() != null) { 
            lands.add(curr.getLAndExpNode()); 
            curr = curr.getLOrExpNode();
        }
        lands.add(curr.getLAndExpNode()); 
        
        for (int i = 0; i < lands.size() - 1; i++) {
            IrBasicBlock nextTest = IrBuilder.GetNewBasicBlockIr();
            VisitLAndExp(lands.get(i), trueTarget, nextTest);
            IrBuilder.SetCurrentBasicBlock(nextTest);
        }
        VisitLAndExp(lands.get(lands.size() - 1), trueTarget, falseTarget);
    }
    
    private static void VisitLAndExp(LAndExpNode node, IrBasicBlock trueTarget, IrBasicBlock falseTarget) {
        ArrayList<EqExpNode> eqs = new ArrayList<>();
        LAndExpNode curr = node;
        while (curr.getLAndExpNode() != null) {
            eqs.add(curr.getEqExpNode());
            curr = curr.getLAndExpNode();
        }
        eqs.add(curr.getEqExpNode());
        
        for (int i = 0; i < eqs.size() - 1; i++) {
            IrBasicBlock nextTest = IrBuilder.GetNewBasicBlockIr();
            VisitEqExp(eqs.get(i), nextTest, falseTarget);
            IrBuilder.SetCurrentBasicBlock(nextTest);
        }
        VisitEqExp(eqs.get(eqs.size() - 1), trueTarget, falseTarget);
    }
    
    private static void VisitEqExp(EqExpNode node, IrBasicBlock trueTarget, IrBasicBlock falseTarget) {
        EqExpNode curr = node;
        ArrayList<RelExpNode> rels = new ArrayList<>();
        ArrayList<TokenType> ops = new ArrayList<>();
        
        rels.add(curr.getRelExpNode());
        while (curr.getEqExpNode() != null) {
            ops.add(curr.getOperator().getTokenType());
            curr = curr.getEqExpNode();
            rels.add(curr.getRelExpNode());
        }
        
        IrValue val = VisitRelExp(rels.get(0));
        for (int i = 0; i < ops.size(); i++) {
            IrValue rhs = VisitRelExp(rels.get(i + 1));
            String op = (ops.get(i) == TokenType.EQL) ? "==" : "!=";
            Instr instr = new CompareInstr(op, val, rhs);
            
            Instr zext = new ExtendInstr(instr, IrBaseType.INT32);
            val = zext;
        }
        
        Instr cmp = new CompareInstr("!=", val, new IrConstantInt(0));
        new BranchInstr(cmp, trueTarget, falseTarget);
    }
    
    private static IrValue VisitRelExp(RelExpNode node) {
        RelExpNode curr = node;
        ArrayList<AddExpNode> adds = new ArrayList<>();
        ArrayList<TokenType> ops = new ArrayList<>();
        
        adds.add(curr.getAddExpNode());
        while (curr.getRelExpNode() != null) {
            ops.add(curr.getOperator().getTokenType());
            curr = curr.getRelExpNode();
            adds.add(curr.getAddExpNode());
        }
        
        IrValue val = VisitAddExp(adds.get(0));
        for (int i = 0; i < ops.size(); i++) {
            IrValue rhs = VisitAddExp(adds.get(i + 1));
            String op;
            switch (ops.get(i)) {
                case LSS: op = "<"; break;
                case LEQ: op = "<="; break;
                case GRE: op = ">"; break;
                case GEQ: op = ">="; break;
                default: op = "<"; 
            }
            Instr instr = new CompareInstr(op, val, rhs);
            Instr zext = new ExtendInstr(instr, IrBaseType.INT32);
            val = zext;
        }
        return val;
    }
}
