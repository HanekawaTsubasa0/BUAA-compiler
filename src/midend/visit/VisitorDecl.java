package midend.visit;

import ASTNode.*;
import midend.gen.Evaluator;
import midend.llvm.IrBuilder;
import midend.llvm.instr.AllocateInstr;
import midend.llvm.instr.GepInstr;
import midend.llvm.instr.StoreInstr;
import midend.llvm.type.IrArrayType;
import midend.llvm.type.IrBaseType;
import midend.llvm.type.IrType;
import midend.llvm.constant.IrConstant;
import midend.llvm.constant.IrConstantArray;
import midend.llvm.constant.IrConstantInt;
import midend.llvm.value.IrGlobalValue;
import midend.llvm.value.IrValue;
import midend.symbol.Symbol;
import midend.symbol.SymbolManager;
import midend.symbol.SymbolType;

import java.util.ArrayList;

public class VisitorDecl {

    public static void VisitDecl(DeclNode node) {
        if (node.getConstDecl() != null) {
            VisitConstDecl(node.getConstDecl());
        } else {
            VisitVarDecl(node.getVarDecl());
        }
    }

    private static void VisitConstDecl(ConstDeclNode node) {
        for (ConstDefNode def : node.getConstDefNodes()) {
            VisitConstDef(def);
        }
    }

    private static void VisitConstDef(ConstDefNode node) {
        Symbol symbol = SymbolManager.GetSymbol(node.getIdent().getValue());
        if (symbol == null) return; 

        int arraySize = getArraySize(node.getConstExpNodes());
        boolean isArray = arraySize > 0;
        if (SymbolManager.IsGlobal()) {
            IrConstant initVal = VisitConstInitVal(node.getConstInitValNode(), arraySize);
            IrType type = isArray ? new IrArrayType(arraySize, IrBaseType.INT32) : initVal.GetIrType();
            IrGlobalValue globalValue = IrBuilder.GetNewIrGlobalValue(type, initVal);
            symbol.SetIrValue(globalValue);
        } else {
            IrConstant initVal = VisitConstInitVal(node.getConstInitValNode(), arraySize);
            if (initVal instanceof IrConstantArray) {
                IrType type = initVal.GetIrType();
                AllocateInstr alloca = new AllocateInstr(type);
                symbol.SetIrValue(alloca);
                InitializeLocalArray(alloca, (IrConstantArray) initVal);
            } else {
                symbol.SetIrValue(initVal);
            }
        }
    }

    private static IrConstant VisitConstInitVal(ConstInitValNode node, int arraySize) {
        // AST structure: ConstInitVal -> ConstExp | '{' ConstExp, ... '}'
        // If leftBraceToken is null, it's a scalar ConstExp.
        // If not null, it's an array init with a flat list of ConstExpNodes.
        
        if (node.getLeftBraceToken() == null) {
            // Scalar
            if (!node.getConstExpNodes().isEmpty()) {
                int val = Evaluator.evalConstExp(node.getConstExpNodes().get(0));
                return new IrConstantInt(val);
            }
            return new IrConstantInt(0); // Should not happen
        } else {
            // Array Init
            ArrayList<IrConstant> list = new ArrayList<>();
            for (ConstExpNode exp : node.getConstExpNodes()) {
                int val = Evaluator.evalConstExp(exp);
                list.add(new IrConstantInt(val));
            }
            if (arraySize > 0 && list.size() > arraySize) {
                list = new ArrayList<>(list.subList(0, arraySize));
            }
            int size = arraySize > 0 ? arraySize : list.size();
            return new IrConstantArray(size, IrBaseType.INT32, IrBuilder.GetGlobalVarName(), list); 
        }
    }

    private static void VisitVarDecl(VarDeclNode node) {
        for (VarDefNode def : node.getVarDefNodes()) {
            VisitVarDef(def);
        }
    }

    private static void VisitVarDef(VarDefNode node) {
        Symbol symbol = SymbolManager.GetSymbol(node.getIdent().getValue());
        
        boolean isStatic = false;
        if (symbol.GetSymbolType() == SymbolType.STATIC_INT || 
            symbol.GetSymbolType() == SymbolType.STATIC_INT_ARRAY) {
            isStatic = true;
        }
        
        boolean isGlobal = SymbolManager.IsGlobal();
        int arraySize = getArraySize(node.getConstExpNodes());
        boolean isArray = arraySize > 0;
        
        if (isGlobal || isStatic) {
            IrConstant initVal;
            if (node.getInitValNode() != null) {
                 initVal = EvalGlobalInit(node.getInitValNode(), arraySize);
            } else {
                 if (isArray) {
                     initVal = new IrConstantArray(arraySize, IrBaseType.INT32, IrBuilder.GetGlobalVarName(), new ArrayList<>());
                 } else {
                     initVal = new IrConstantInt(0);
                 }
            }
            IrType type = isArray ? new IrArrayType(arraySize, IrBaseType.INT32) : IrBaseType.INT32;
            IrGlobalValue global = IrBuilder.GetNewIrGlobalValue(type, initVal);
            symbol.SetIrValue(global);
        } else {
            IrType type = isArray ? new IrArrayType(arraySize, IrBaseType.INT32) : IrBaseType.INT32;
            AllocateInstr alloca = new AllocateInstr(type);
            symbol.SetIrValue(alloca);
            
            if (node.getInitValNode() != null) {
                VisitInitVal(node.getInitValNode(), alloca, arraySize);
            }
        }
    }
    
    private static IrConstant EvalGlobalInit(InitValNode node, int arraySize) {
        if (node.getLeftBraceToken() == null) {
            if (!node.getExpNodes().isEmpty()) {
                return new IrConstantInt(Evaluator.eval(node.getExpNodes().get(0)));
            }
            return new IrConstantInt(0);
        }
        ArrayList<IrConstant> list = new ArrayList<>();
        for (ExpNode exp : node.getExpNodes()) {
            list.add(new IrConstantInt(Evaluator.eval(exp)));
        }
        if (arraySize > 0 && list.size() > arraySize) {
            list = new ArrayList<>(list.subList(0, arraySize));
        }
        int size = arraySize > 0 ? arraySize : list.size();
        return new IrConstantArray(size, IrBaseType.INT32, IrBuilder.GetGlobalVarName(), list);
    }
    
    private static void VisitInitVal(InitValNode node, IrValue addr, int arraySize) {
        if (node.getLeftBraceToken() == null) {
            if (!node.getExpNodes().isEmpty()) {
                IrValue val = VisitorExp.VisitExp(node.getExpNodes().get(0));
                new StoreInstr(val, addr);
            }
        } else {
            // Array Init
            // The AST gives us a flat list of ExpNodes.
            // We iterate and GEP + Store.
            int index = 0;
            for (ExpNode exp : node.getExpNodes()) {
                if (arraySize > 0 && index >= arraySize) {
                    break;
                }
                IrValue elementPtr = new GepInstr(addr, new IrConstantInt(index));
                IrValue val = VisitorExp.VisitExp(exp);
                new StoreInstr(val, elementPtr);
                index++;
            }
            for (int i = index; i < arraySize; i++) {
                IrValue elementPtr = new GepInstr(addr, new IrConstantInt(i));
                new StoreInstr(new IrConstantInt(0), elementPtr);
            }
        }
    }
    
    private static void InitializeLocalArray(IrValue addr, IrConstantArray array) {
        for (int i = 0; i < array.GetArraySize(); i++) {
            // addr is [n x i32]*
            // We want i-th element.
            // GepInstr(addr, i) -> gep addr, 0, i -> i32*
            IrValue ptr = new GepInstr(addr, new IrConstantInt(i));
            // No need to AddInstr(ptr) if GepInstr constructor does it?
            // Checking Instr.java: `super` calls `IrBuilder.AddInstr(this)`.
            // Yes.
            
            IrConstant val = array.GetElement(i);
            if (val instanceof IrConstantArray) {
                // Nested? Not supported by current flat AST logic, but if IrConstantArray supports it:
                // Recursive init
                // But current GepInstr logic with ArrayType always does `0, i`.
                // If `ptr` is `i32*`, we can't do `gep ptr, 0, j` (target not array).
                // So this only works for 1D.
                // Given the AST limitation, we likely only support 1D.
                InitializeLocalArray(ptr, (IrConstantArray) val);
            } else {
                new StoreInstr(val, ptr);
            }
        }
    }

    private static int getArraySize(ArrayList<ConstExpNode> constExpNodes) {
        if (constExpNodes == null || constExpNodes.isEmpty()) {
            return 0;
        }
        return Evaluator.evalConstExp(constExpNodes.get(0));
    }
}
