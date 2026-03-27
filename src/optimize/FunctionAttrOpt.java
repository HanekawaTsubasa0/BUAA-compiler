package optimize;

import midend.llvm.instr.AllocateInstr;
import midend.llvm.instr.CallInstr;
import midend.llvm.instr.GepInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.LoadInstr;
import midend.llvm.instr.MoveInstr;
import midend.llvm.instr.StoreInstr;
import midend.llvm.instr.io.IoInstr;
import midend.llvm.instr.phi.PhiInstr;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrGlobalValue;
import midend.llvm.value.IrParameter;
import midend.llvm.value.IrValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

public class FunctionAttrOpt extends Optimizer {
    private final HashMap<IrFunction, HashSet<IrFunction>> calleeMap = new HashMap<>();
    private final HashMap<IrValue, Boolean> localPointerCache = new HashMap<>();

    @Override
    public void Optimize() {
        BuildLocalInfo();
        PropagatePurity();
        MarkRecursion();
        RemovePureCall();
    }

    private void BuildLocalInfo() {
        calleeMap.clear();
        localPointerCache.clear();
        for (IrFunction function : irModule.GetFunctions()) {
            calleeMap.put(function, new HashSet<>());
        }
        for (IrFunction function : irModule.GetFunctions()) {
            boolean localPure = true;
            HashSet<IrFunction> callees = calleeMap.get(function);
            for (IrBasicBlock block : function.GetBasicBlocks()) {
                for (Instr instr : block.GetInstrList()) {
                    if (instr instanceof IoInstr) {
                        localPure = false;
                    } else if (instr instanceof LoadInstr loadInstr) {
                        if (!IsLocalPointer(loadInstr.GetPointer())) {
                            localPure = false;
                        }
                    } else if (instr instanceof StoreInstr storeInstr) {
                        if (!IsLocalPointer(storeInstr.GetAddressValue())) {
                            localPure = false;
                        }
                    } else if (instr instanceof CallInstr callInstr) {
                        IrFunction callee = ResolveCallee(callInstr.GetTargetFunction());
                        if (callee == null) {
                            localPure = false;
                        } else {
                            callees.add(callee);
                        }
                    }
                }
            }
            function.SetPure(localPure);
            function.SetLeaf(callees.isEmpty());
        }
    }

    private void PropagatePurity() {
        boolean changed;
        do {
            changed = false;
            for (IrFunction function : irModule.GetFunctions()) {
                if (!function.IsPure()) {
                    continue;
                }
                HashSet<IrFunction> callees = calleeMap.get(function);
                if (callees == null) {
                    continue;
                }
                for (IrFunction callee : callees) {
                    if (!callee.IsPure()) {
                        function.SetPure(false);
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
    }

    private void MarkRecursion() {
        for (IrFunction function : irModule.GetFunctions()) {
            function.SetRecursive(CanReachSelf(function));
        }
    }

    private boolean CanReachSelf(IrFunction start) {
        HashSet<IrFunction> visited = new HashSet<>();
        return DfsReach(start, start, visited);
    }

    private boolean DfsReach(IrFunction start, IrFunction current,
                             HashSet<IrFunction> visited) {
        if (!visited.add(current)) {
            return false;
        }
        HashSet<IrFunction> callees = calleeMap.get(current);
        if (callees == null) {
            return false;
        }
        for (IrFunction callee : callees) {
            if (callee == start) {
                return true;
            }
            if (DfsReach(start, callee, visited)) {
                return true;
            }
        }
        return false;
    }

    private void RemovePureCall() {
        for (IrFunction function : irModule.GetFunctions()) {
            for (IrBasicBlock block : function.GetBasicBlocks()) {
                Iterator<Instr> iterator = block.GetInstrList().iterator();
                while (iterator.hasNext()) {
                    Instr instr = iterator.next();
                    if (!(instr instanceof CallInstr callInstr)) {
                        continue;
                    }
                    IrFunction callee = ResolveCallee(callInstr.GetTargetFunction());
                    if (callee == null || !callee.IsPure()) {
                        continue;
                    }
                    if (!callInstr.GetUseList().isEmpty()) {
                        continue;
                    }
                    callInstr.RemoveAllValueUse();
                    iterator.remove();
                }
            }
        }
    }

    private IrFunction ResolveCallee(IrFunction callee) {
        if (callee == null) {
            return null;
        }
        if (calleeMap.containsKey(callee)) {
            return callee;
        }
        for (IrFunction function : irModule.GetFunctions()) {
            if (function.GetIrName().equals(callee.GetIrName())) {
                return function;
            }
        }
        return null;
    }

    private boolean IsLocalPointer(IrValue value) {
        if (value == null) {
            return false;
        }
        if (localPointerCache.containsKey(value)) {
            return localPointerCache.get(value);
        }
        boolean result = IsLocalPointer(value, new HashSet<>());
        localPointerCache.put(value, result);
        return result;
    }

    private boolean IsLocalPointer(IrValue value, HashSet<IrValue> visiting) {
        if (value == null) {
            return false;
        }
        if (!visiting.add(value)) {
            return false;
        }
        if (value instanceof AllocateInstr) {
            return true;
        }
        if (value instanceof GepInstr gepInstr) {
            return IsLocalPointer(gepInstr.GetPointer(), visiting);
        }
        if (value instanceof MoveInstr moveInstr) {
            return IsLocalPointer(moveInstr.GetSrcValue(), visiting);
        }
        if (value instanceof PhiInstr phiInstr) {
            ArrayList<IrValue> values = phiInstr.GetUseValueList();
            if (values.isEmpty()) {
                return false;
            }
            for (IrValue incoming : values) {
                if (!IsLocalPointer(incoming, visiting)) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof IrGlobalValue || value instanceof IrParameter ||
            value instanceof LoadInstr) {
            return false;
        }
        return false;
    }
}
