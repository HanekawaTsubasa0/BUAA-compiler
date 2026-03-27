package midend.visit;

import ASTNode.*;
import midend.llvm.IrBuilder;
import midend.llvm.constant.IrConstantInt;
import midend.llvm.constant.IrConstantString;
import midend.llvm.instr.JumpInstr;
import midend.llvm.instr.ReturnInstr;
import midend.llvm.instr.StoreInstr;
import midend.llvm.instr.io.GetIntInstr;
import midend.llvm.instr.io.PrintCharInstr;
import midend.llvm.instr.io.PrintIntInstr;
import midend.llvm.instr.io.PrintStrInstr;
import midend.llvm.type.IrBaseType;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrLoop;
import midend.llvm.value.IrValue;
import midend.symbol.SymbolManager;

import java.util.ArrayList;

public class VisitorStmt {
    public static void VisitStmt(StmtNode node) {
        switch (node.getType()) {
            case Block:
                SymbolManager.EnterSonScope();
                VisitorBlock.VisitBlock(node.getBlockNode());
                SymbolManager.GoToFatherSymbolTable();
                break;
            case LValAssignExp:
                IrValue lVal = VisitorLVal.VisitLVal(node.getLValNode());
                IrValue exp = VisitorExp.VisitExp(node.getExpNode());
                new StoreInstr(exp, lVal);
                break;
            case LValAssignGetint:
                IrValue lValG = VisitorLVal.VisitLVal(node.getLValNode());
                GetIntInstr getint = new GetIntInstr();
                new StoreInstr(getint, lValG);
                break;
            case Exp:
                if (node.getExpNode() != null) VisitorExp.VisitExp(node.getExpNode());
                break;
            case Return:
                IrValue retVal = null;
                if (node.getExpNode() != null) {
                    retVal = VisitorExp.VisitExp(node.getExpNode());
                } else if (IrBuilder.GetCurrentFunctionReturnType().IsInt32Type()) {
                    retVal = new IrConstantInt(0);
                }
                new ReturnInstr(retVal);
                break;
            case Printf:
                VisitPrintf(node);
                break;
            case If:
                VisitIf(node);
                break;
            case For:
                VisitFor(node);
                break;
            case Break:
                if (IrBuilder.LoopStackPeek() != null) {
                    new JumpInstr(IrBuilder.LoopStackPeek().GetFollowBlock());
                    IrBuilder.SetCurrentBasicBlock(IrBuilder.GetNewBasicBlockIr());
                }
                break;
            case Continue:
                 if (IrBuilder.LoopStackPeek() != null) {
                    new JumpInstr(IrBuilder.LoopStackPeek().GetStepBlock());
                    IrBuilder.SetCurrentBasicBlock(IrBuilder.GetNewBasicBlockIr());
                }
                break;
        }
    }

    private static void VisitIf(StmtNode node) {
        IrBasicBlock trueBlock = IrBuilder.GetNewBasicBlockIr();
        IrBasicBlock falseBlock = (node.getElseToken() != null) ? IrBuilder.GetNewBasicBlockIr() : null;
        IrBasicBlock nextBlock = IrBuilder.GetNewBasicBlockIr();
        
        IrBasicBlock condFalseTarget = (falseBlock != null) ? falseBlock : nextBlock;
        
        VisitorExp.VisitCond(node.getCondNode(), trueBlock, condFalseTarget);
        
        // True
        IrBuilder.SetCurrentBasicBlock(trueBlock);
        VisitStmt(node.getStmtNodes().get(0));
        new JumpInstr(nextBlock);
        
        // False
        if (falseBlock != null) {
            IrBuilder.SetCurrentBasicBlock(falseBlock);
            VisitStmt(node.getStmtNodes().get(1));
            new JumpInstr(nextBlock);
        }
        
        IrBuilder.SetCurrentBasicBlock(nextBlock);
    }

    private static void VisitFor(StmtNode node) {
        // Init
        if (node.getType() != StmtNode.StmtType.For) return;

        if (node.getForStmtNode1() != null) {
            VisitForStmtNode(node.getForStmtNode1());
        }

        IrBasicBlock condBlock = IrBuilder.GetNewBasicBlockIr();
        IrBasicBlock bodyBlock = IrBuilder.GetNewBasicBlockIr();
        IrBasicBlock stepBlock = IrBuilder.GetNewBasicBlockIr();
        IrBasicBlock nextBlock = IrBuilder.GetNewBasicBlockIr();
        
        IrBuilder.LoopStackPush(new IrLoop(condBlock, bodyBlock, stepBlock, nextBlock));
        
        new JumpInstr(condBlock);
        IrBuilder.SetCurrentBasicBlock(condBlock);
        
        // Cond
        if (node.getCondNode() != null) {
             VisitorExp.VisitCond(node.getCondNode(), bodyBlock, nextBlock);
        } else {
             new JumpInstr(bodyBlock);
        }
        
        IrBuilder.SetCurrentBasicBlock(bodyBlock);
        VisitStmt(node.getStmtNodes().get(0));
        new JumpInstr(stepBlock);
        
        IrBuilder.SetCurrentBasicBlock(stepBlock);
        if (node.getForStmtNode2() != null) {
            VisitForStmtNode(node.getForStmtNode2());
        }
        new JumpInstr(condBlock);
        
        IrBuilder.SetCurrentBasicBlock(nextBlock);
        IrBuilder.LoopStackPop();
    }
    
    private static void VisitForStmtNode(ForStmtNode node) {
        for (int i = 0; i < node.getLValNodes().size(); i++) {
            IrValue lVal = VisitorLVal.VisitLVal(node.getLValNodes().get(i));
            IrValue exp = VisitorExp.VisitExp(node.getExpNodes().get(i));
            new StoreInstr(exp, lVal);
        }
    }

    private static void VisitPrintf(StmtNode node) {
        String format = node.getFormatString().getValue();
        format = format.substring(1, format.length() - 1); // remove quotes

        ArrayList<ExpNode> args = node.getExpNodes();
        ArrayList<IrValue> argValues = new ArrayList<>();
        for (ExpNode arg : args) {
            argValues.add(VisitorExp.VisitExp(arg));
        }

        int argIndex = 0;
        StringBuilder buffer = new StringBuilder();
        
        for (int i = 0; i < format.length(); i++) {
            if (format.charAt(i) == '%' && i + 1 < format.length() && format.charAt(i + 1) == 'd') {
                if (buffer.length() > 0) {
                    EmitPutStr(buffer.toString());
                    buffer.setLength(0);
                }
                if (argIndex < argValues.size()) {
                    IrValue val = argValues.get(argIndex++);
                    new PrintIntInstr(val);
                }
                i++;
            } else if (format.charAt(i) == '%' && i + 1 < format.length() && format.charAt(i + 1) == 'c') {
                 if (buffer.length() > 0) {
                    EmitPutStr(buffer.toString());
                    buffer.setLength(0);
                }
                if (argIndex < argValues.size()) {
                    IrValue val = argValues.get(argIndex++);
                    new PrintCharInstr(val);
                }
                i++;
            } else if (format.charAt(i) == '\\' && i + 1 < format.length() && format.charAt(i + 1) == 'n') {
                buffer.append("\\n");
                i++;
            } else {
                buffer.append(format.charAt(i));
            }
        }
        if (buffer.length() > 0) {
            EmitPutStr(buffer.toString());
        }
    }
    
    private static void EmitPutStr(String s) {
        if (s.isEmpty()) return;
        IrConstantString constStr = IrBuilder.GetCurrentModule().GetNewIrConstantString(s);
        new PrintStrInstr(constStr);
    }
}
