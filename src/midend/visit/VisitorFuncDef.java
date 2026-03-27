package midend.visit;

import ASTNode.*;
import midend.llvm.IrBuilder;
import midend.llvm.instr.AllocateInstr;
import midend.llvm.instr.StoreInstr;
import midend.llvm.type.IrBaseType;
import midend.llvm.type.IrType;
import midend.llvm.type.IrPointerType;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrParameter;
import midend.symbol.FuncSymbol;
import midend.symbol.FuncSymbol;
import midend.symbol.Symbol;
import midend.symbol.SymbolManager;
import midend.symbol.SymbolType;

public class VisitorFuncDef {

    public static void VisitFuncDef(FuncDefNode node) {
        SymbolManager.EnterFunc(SymbolType.GetFuncType(node.getFuncTypeNode().getType()));
        SymbolManager.EnterSonScope();
        
        String name = node.getIdent().getValue();
        IrType retType = node.getFuncTypeNode().getType().equals("void") ? IrBaseType.VOID : IrBaseType.INT32;

        Symbol funcSym = SymbolManager.GetSymbol(name);
        IrFunction func;
        if (funcSym != null && funcSym.GetIrValue() instanceof IrFunction) {
            func = (IrFunction) funcSym.GetIrValue();
            IrBuilder.BeginFunction(func);
        } else {
            func = IrBuilder.GetNewFunctionIr(name, retType);
            if (funcSym != null) {
                funcSym.SetIrValue(func);
            }
        }
        
        // Params
        if (node.getFuncFParamsNode() != null) {
            for (FuncFParamNode param : node.getFuncFParamsNode().getFuncFParamNodes()) {
                String pName = param.getIdent().getValue();
                Symbol pSym = SymbolManager.GetSymbol(pName);
                
                IrType pType = IrBaseType.INT32;
                if (param.getLeftBrackets().size() > 0) {
                     pType = new IrPointerType(IrBaseType.INT32); 
                }
                
                IrParameter irParam = new IrParameter(pType, IrBuilder.GetLocalVarName());
                func.AddParameter(irParam);
                
                AllocateInstr alloca = new AllocateInstr(pType);
                new StoreInstr(irParam, alloca);
                
                pSym.SetIrValue(alloca);
            }
        }
        
        VisitorBlock.VisitBlock(node.getBlockNode());
        
        func.CheckHaveReturn();
        func.CheckNoEmptyBlock();
        
        SymbolManager.GoToFatherSymbolTable();
        SymbolManager.LeaveFunc();
    }
    
    public static void VisitMainFuncDef(MainFuncDefNode node) {
        SymbolManager.EnterFunc(SymbolType.INT_FUNC);
        SymbolManager.EnterSonScope();
        
        IrFunction func = IrBuilder.GetNewFunctionIr("main", IrBaseType.INT32);
        VisitorBlock.VisitBlock(node.getBlockNode());
        
        func.CheckHaveReturn();
        func.CheckNoEmptyBlock();
        
        SymbolManager.GoToFatherSymbolTable();
        SymbolManager.LeaveFunc();
    }
}
