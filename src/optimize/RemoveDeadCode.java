package optimize;

import midend.llvm.constant.IrConstant;
import midend.llvm.instr.BranchInstr;
import midend.llvm.instr.CallInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.JumpInstr;
import midend.llvm.instr.ReturnInstr;
import midend.llvm.instr.StoreInstr;
import midend.llvm.instr.io.IoInstr;
import midend.llvm.instr.phi.PhiInstr;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Stack;

public class RemoveDeadCode extends Optimizer {
    private final HashMap<IrFunction, HashSet<IrFunction>> calleeMap;
    private final HashMap<IrFunction, HashSet<IrFunction>> callerMap;
    private final HashSet<IrFunction> sideEffectFunctions;

    public RemoveDeadCode() {
        this.calleeMap = new HashMap<>();
        this.callerMap = new HashMap<>();
        this.sideEffectFunctions = new HashSet<>();
    }

    @Override
    public void Optimize() {
        boolean finished = false;
        while (!finished) {
            finished = true;
            BuildFunctionCallMap();
            finished &= RemoveUselessFunction();
            finished &= RemoveUselessBlock();
            finished &= RemoveUselessCode();
            finished &= RemoveUselessPhi();
            finished &= RemoveDeadBranch();
            finished &= MergeBlock();
        }
    }

    private void BuildFunctionCallMap() {
        calleeMap.clear();
        callerMap.clear();
        sideEffectFunctions.clear();
        for (IrFunction irFunction : irModule.GetFunctions()) {
            calleeMap.put(irFunction, new HashSet<>());
            callerMap.put(irFunction, new HashSet<>());
        }
        DfsSideFunction(irModule.GetMainFunction(), new HashSet<>());
    }

    private void DfsSideFunction(IrFunction visitFunction, HashSet<IrFunction> visited) {
        if (visited.contains(visitFunction)) {
            return;
        }
        visited.add(visitFunction);

        for (IrBasicBlock irBasicBlock : visitFunction.GetBasicBlocks()) {
            for (Instr instr : irBasicBlock.GetInstrList()) {
                if (instr instanceof CallInstr callInstr) {
                    IrFunction callee = ResolveCallee(callInstr.GetTargetFunction());
                    HashSet<IrFunction> calleeSet = calleeMap.get(visitFunction);
                    HashSet<IrFunction> callerSet = callerMap.get(callee);
                    if (callee == null || calleeSet == null || callerSet == null) {
                        // External or unknown call target: conservatively keep caller side effects.
                        sideEffectFunctions.add(visitFunction);
                        continue;
                    }
                    DfsSideFunction(callee, visited);
                    calleeSet.add(callee);
                    callerSet.add(visitFunction);
                    if (sideEffectFunctions.contains(callee)) {
                        sideEffectFunctions.add(visitFunction);
                    }
                } else if (instr instanceof IoInstr || instr instanceof StoreInstr) {
                    sideEffectFunctions.add(visitFunction);
                }
            }
        }
    }

    private IrFunction ResolveCallee(IrFunction callee) {
        if (callee == null) {
            return null;
        }
        if (callerMap.containsKey(callee)) {
            return callee;
        }
        for (IrFunction irFunction : irModule.GetFunctions()) {
            if (irFunction.GetIrName().equals(callee.GetIrName())) {
                return irFunction;
            }
        }
        return null;
    }

    private boolean RemoveUselessFunction() {
        boolean finished = true;
        Iterator<IrFunction> iterator = irModule.GetFunctions().iterator();
        while (iterator.hasNext()) {
            IrFunction irFunction = iterator.next();
            if (!irFunction.IsMainFunction() && callerMap.get(irFunction).isEmpty()) {
                iterator.remove();
                finished = false;
            }
        }
        return finished;
    }

    private boolean RemoveUselessBlock() {
        boolean finished = true;
        for (IrFunction irFunction : irModule.GetFunctions()) {
            HashSet<IrBasicBlock> referenced = GetReferencedBlocks(irFunction);
            Iterator<IrBasicBlock> iterator = irFunction.GetBasicBlocks().iterator();
            while (iterator.hasNext()) {
                IrBasicBlock visitBlock = iterator.next();
                if (visitBlock.GetBeforeBlocks().isEmpty() && !visitBlock.IsEntryBlock() &&
                    !referenced.contains(visitBlock)) {
                    for (IrBasicBlock nextBlock : visitBlock.GetNextBlocks()) {
                        nextBlock.GetBeforeBlocks().remove(visitBlock);
                        for (Instr nextInstr : nextBlock.GetInstrList()) {
                            if (nextInstr instanceof PhiInstr phiInstr) {
                                phiInstr.RemoveBlock(visitBlock);
                            }
                        }
                    }
                    for (Instr instr : visitBlock.GetInstrList()) {
                        instr.RemoveAllValueUse();
                    }

                    finished = false;
                    iterator.remove();
                }
            }
        }
        return finished;
    }

    private HashSet<IrBasicBlock> GetReferencedBlocks(IrFunction function) {
        HashSet<IrBasicBlock> referenced = new HashSet<>();
        for (IrBasicBlock block : function.GetBasicBlocks()) {
            for (Instr instr : block.GetInstrList()) {
                if (instr instanceof JumpInstr jumpInstr) {
                    if (!jumpInstr.GetUseValueList().isEmpty()) {
                        referenced.add(jumpInstr.GetTargetBlock());
                    }
                } else if (instr instanceof BranchInstr branchInstr) {
                    if (branchInstr.GetUseValueList().size() >= 3) {
                        referenced.add(branchInstr.GetTrueBlock());
                        referenced.add(branchInstr.GetFalseBlock());
                    }
                }
            }
        }
        return referenced;
    }

    private boolean RemoveUselessCode() {
        boolean finished = true;
        HashSet<Instr> activeInstrSet = GetActiveInstrSet();

        for (IrFunction irFunction : irModule.GetFunctions()) {
            for (IrBasicBlock irBasicBlock : irFunction.GetBasicBlocks()) {
                Iterator<Instr> iterator = irBasicBlock.GetInstrList().iterator();
                while (iterator.hasNext()) {
                    Instr instr = iterator.next();
                    if (!activeInstrSet.contains(instr)) {
                        instr.RemoveAllValueUse();
                        iterator.remove();
                        finished = false;
                    }
                }
            }
        }
        return finished;
    }

    private HashSet<Instr> GetActiveInstrSet() {
        HashSet<Instr> activeInstrSet = new HashSet<>();
        Stack<Instr> todoInstrStack = new Stack<>();
        for (IrFunction irFunction : irModule.GetFunctions()) {
            for (IrBasicBlock irBasicBlock : irFunction.GetBasicBlocks()) {
                for (Instr instr : irBasicBlock.GetInstrList()) {
                    if (IsCriticalInstr(instr)) {
                        todoInstrStack.push(instr);
                    }
                }
            }
        }

        while (!todoInstrStack.isEmpty()) {
            Instr todoInstr = todoInstrStack.pop();
            activeInstrSet.add(todoInstr);
            for (IrValue useValue : todoInstr.GetUseValueList()) {
                if (useValue instanceof Instr useInstr) {
                    if (!activeInstrSet.contains(useInstr)) {
                        todoInstrStack.push(useInstr);
                    }
                    activeInstrSet.add(useInstr);
                }
            }
        }
        return activeInstrSet;
    }

    private boolean IsCriticalInstr(Instr instr) {
        return instr instanceof ReturnInstr ||
            (instr instanceof CallInstr callInstr &&
                sideEffectFunctions.contains(callInstr.GetTargetFunction())) ||
            instr instanceof BranchInstr || instr instanceof JumpInstr ||
            instr instanceof StoreInstr || instr instanceof IoInstr;
    }

    private boolean RemoveUselessPhi() {
        boolean finished = true;
        for (IrFunction irFunction : irModule.GetFunctions()) {
            for (IrBasicBlock irBasicBlock : irFunction.GetBasicBlocks()) {
                Iterator<Instr> iterator = irBasicBlock.GetInstrList().iterator();
                while (iterator.hasNext()) {
                    Instr instr = iterator.next();
                    if (!(instr instanceof PhiInstr phiInstr)) {
                        continue;
                    }

                    ArrayList<IrValue> phiValueList = phiInstr.GetUseValueList();
                    if (phiValueList.size() == 1) {
                        finished = false;
                        phiInstr.ModifyAllUsersToNewValue(phiValueList.get(0));
                        phiInstr.RemoveAllValueUse();
                        iterator.remove();
                    }
                }
            }
        }
        return finished;
    }

    private boolean RemoveDeadBranch() {
        boolean finished = true;

        for (IrFunction irFunction : irModule.GetFunctions()) {
            for (IrBasicBlock irBasicBlock : irFunction.GetBasicBlocks()) {
                Instr instr = irBasicBlock.GetLastInstr();
                if (!(instr instanceof BranchInstr branchInstr)) {
                    continue;
                }
                IrValue cond = branchInstr.GetCond();
                IrBasicBlock trueBlock = branchInstr.GetTrueBlock();
                IrBasicBlock falseBlock = branchInstr.GetFalseBlock();

                if (cond instanceof IrConstant) {
                    int condValue = Integer.parseInt(cond.GetIrName());
                    JumpInstr jumpInstr;
                    if (condValue != 0) {
                        jumpInstr = new JumpInstr(trueBlock, irBasicBlock);
                        irBasicBlock.DeleteNextBlock(falseBlock);
                        for (Instr nextInstr : falseBlock.GetInstrList()) {
                            if (nextInstr instanceof PhiInstr phiInstr) {
                                phiInstr.RemoveBlock(irBasicBlock);
                            }
                        }
                    } else {
                        jumpInstr = new JumpInstr(falseBlock, irBasicBlock);
                        irBasicBlock.DeleteNextBlock(trueBlock);
                        for (Instr nextInstr : trueBlock.GetInstrList()) {
                            if (nextInstr instanceof PhiInstr phiInstr) {
                                phiInstr.RemoveBlock(irBasicBlock);
                            }
                        }
                    }
                    irBasicBlock.ReplaceLastInstr(jumpInstr);
                    finished = false;
                }
            }
        }

        return finished;
    }

    private boolean MergeBlock() {
        boolean finished = true;
        for (IrFunction irFunction : irModule.GetFunctions()) {
            Iterator<IrBasicBlock> iterator = irFunction.GetBasicBlocks().iterator();
            while (iterator.hasNext()) {
                IrBasicBlock irBasicBlock = iterator.next();
                if (CanMergeBlock(irBasicBlock)) {
                    finished = false;
                    IrBasicBlock beforeBlock = irBasicBlock.GetBeforeBlocks().get(0);
                    beforeBlock.AppendBlock(irBasicBlock);
                    iterator.remove();
                }
            }
        }
        return finished;
    }

    private boolean CanMergeBlock(IrBasicBlock visitBlock) {
        ArrayList<IrBasicBlock> beforeBlockList = visitBlock.GetBeforeBlocks();
        if (beforeBlockList.size() == 1) {
            IrBasicBlock beforeBlock = beforeBlockList.get(0);
            if (beforeBlock == visitBlock) {
                return false;
                }
            return beforeBlock.GetNextBlocks().size() == 1 &&
                beforeBlock.GetNextBlocks().get(0) == visitBlock;
        }
        return false;
    }
}
