package midend.visit;

import ASTNode.*;
import midend.llvm.IrBuilder;
import midend.llvm.constant.IrConstantInt;
import midend.llvm.instr.GepInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.LoadInstr;
import midend.llvm.type.IrArrayType;
import midend.llvm.type.IrPointerType;
import midend.llvm.type.IrType;
import midend.llvm.value.IrValue;
import midend.symbol.Symbol;
import midend.symbol.SymbolManager;

public class VisitorLVal {
    public static IrValue VisitLVal(LValNode node) {
        Symbol symbol = SymbolManager.GetSymbol(node.getIdent().getValue());
        if (symbol == null || symbol.GetIrValue() == null) {
            symbol = SymbolManager.GetSymbolFromFather(node.getIdent().getValue());
        }
        if (symbol == null || symbol.GetIrValue() == null) {
            throw new RuntimeException("Undefined LVal in IR generation: " + node.getIdent().getValue());
        }
        IrValue addr = symbol.GetIrValue();

        if (!node.getExpNodes().isEmpty() && addr.GetIrType() instanceof IrPointerType) {
            IrType target = ((IrPointerType) addr.GetIrType()).GetTargetType();
            if (target instanceof IrPointerType) {
                addr = new LoadInstr(addr);
            }
        }
        
        for (ExpNode exp : node.getExpNodes()) {
            IrValue idx = VisitorExp.VisitExp(exp);
            
            if (addr.GetIrType() instanceof IrPointerType) {
                 IrType target = ((IrPointerType)addr.GetIrType()).GetTargetType();
                 if (target instanceof IrArrayType) {
                     addr = new GepInstr(addr, new IrConstantInt(0)); 
                     addr = new GepInstr(addr, idx);
                 } else {
                     addr = new GepInstr(addr, idx);
                 }
            }
        }
        return addr;
    }
}
