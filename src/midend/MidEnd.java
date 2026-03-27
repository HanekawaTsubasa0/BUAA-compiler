package midend;

import ASTNode.CompUnitNode;
import midend.symbol.SymbolManager;

public class MidEnd {
    private static midend.llvm.IrModule irModule;

    public static void generateSymbolTable(CompUnitNode root) {
        SymbolManager.Init();
        root.buildSymbolTable();
    }

    public static midend.llvm.IrModule generateIR(CompUnitNode root) {
        irModule = new midend.llvm.IrModule();
        midend.llvm.IrBuilder.SetCurrentModule(irModule);
        SymbolManager.Reset();
        
        midend.visit.Visitor visitor = new midend.visit.Visitor(root);
        visitor.Visit();
        
        return irModule;
    }

    public static midend.llvm.IrModule GetIrModule() {
        return irModule;
    }
}
