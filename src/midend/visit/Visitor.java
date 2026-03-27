package midend.visit;

import ASTNode.*;
import midend.llvm.IrBuilder;
import midend.llvm.IrModule;
import midend.llvm.type.IrBaseType;
import midend.llvm.type.IrPointerType;
import midend.llvm.type.IrType;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrParameter;
import midend.symbol.Symbol;
import midend.symbol.SymbolManager;

import java.util.ArrayList;

public class Visitor {
    private final CompUnitNode compUnit;

    public Visitor(CompUnitNode compUnit) {
        this.compUnit = compUnit;
    }

    public void Visit() {
        InitLibFuncs();
        
        ArrayList<DeclNode> decls = this.compUnit.getDeclNodes();
        ArrayList<FuncDefNode> funcDefs = this.compUnit.getFuncDefNodes();
        MainFuncDefNode mainFuncDef = this.compUnit.getMainFuncDefNode();

        for (DeclNode decl : decls) {
            VisitorDecl.VisitDecl(decl);
        }

        for (FuncDefNode funcDef : funcDefs) {
            VisitorFuncDef.VisitFuncDef(funcDef);
        }

        VisitorFuncDef.VisitMainFuncDef(mainFuncDef);
    }
    
    private void InitLibFuncs() {
        createLibFunc("getint", IrBaseType.INT32);
        createLibFunc("putint", IrBaseType.VOID, IrBaseType.INT32);
        createLibFunc("putch", IrBaseType.VOID, IrBaseType.INT32);
        createLibFunc("putstr", IrBaseType.VOID, new IrPointerType(IrBaseType.INT8));
    }
    
    private void createLibFunc(String name, IrType retType, IrType... paramTypes) {
        Symbol symbol = SymbolManager.GetSymbol(name);
        if (symbol != null) {
            IrFunction func = new IrFunction(name, retType);
            for (IrType pt : paramTypes) {
                func.AddParameter(new IrParameter(pt, "arg"));
            }
            symbol.SetIrValue(func);
            // Do not add to module.functions, as they are declarations handled by IrModule.declares
        }
    }
}