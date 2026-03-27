package optimize;

import midend.llvm.constant.IrConstant;
import midend.llvm.constant.IrConstantInt;
import midend.llvm.instr.AluInstr;
import midend.llvm.instr.BranchInstr;
import midend.llvm.instr.CompareInstr;
import midend.llvm.instr.ExtendInstr;
import midend.llvm.instr.GepInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.JumpInstr;
import midend.llvm.instr.ReturnInstr;
import midend.llvm.instr.TruncInstr;
import midend.llvm.instr.phi.PhiInstr;
import midend.llvm.use.IrUse;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;

public class Licm extends Optimizer {
    @Override
    public void Optimize() {
        for (IrFunction function : irModule.GetFunctions()) {
            HoistInFunction(function);
        }
    }

    private void HoistInFunction(IrFunction function) {
        ArrayList<IrBasicBlock> blocks = new ArrayList<>(function.GetBasicBlocks());
        for (IrBasicBlock condBlock : blocks) {
            LoopInfo loopInfo = MatchLoop(condBlock);
            if (loopInfo == null) {
                continue;
            }
            HoistLoopInvariant(loopInfo);
        }
    }

    private LoopInfo MatchLoop(IrBasicBlock condBlock) {
        Instr lastInstr = condBlock.GetLastInstr();
        if (!(lastInstr instanceof BranchInstr branchInstr)) {
            return null;
        }
        IrBasicBlock bodyBlock = branchInstr.GetTrueBlock();
        IrBasicBlock followBlock = branchInstr.GetFalseBlock();

        Instr bodyLast = bodyBlock.GetLastInstr();
        if (!(bodyLast instanceof JumpInstr bodyJump)) {
            return null;
        }
        if (bodyJump.GetUseValueList().isEmpty()) {
            return null;
        }
        IrBasicBlock stepBlock;
        if (bodyJump.GetTargetBlock() == condBlock) {
            stepBlock = bodyBlock;
        } else {
            stepBlock = bodyJump.GetTargetBlock();
            Instr stepLast = stepBlock.GetLastInstr();
            if (!(stepLast instanceof JumpInstr stepJump)) {
                return null;
            }
            if (stepJump.GetUseValueList().isEmpty()) {
                return null;
            }
            if (stepJump.GetTargetBlock() != condBlock) {
                return null;
            }
        }

        ArrayList<IrBasicBlock> preds = condBlock.GetBeforeBlocks();
        if (preds.size() != 2 || !preds.contains(stepBlock)) {
            return null;
        }
        IrBasicBlock preheader = preds.get(0) == stepBlock ? preds.get(1) : preds.get(0);
        if (!(preheader.GetLastInstr() instanceof JumpInstr preJump)) {
            return null;
        }
        if (preJump.GetUseValueList().isEmpty()) {
            return null;
        }
        if (preJump.GetTargetBlock() != condBlock) {
            return null;
        }
        return new LoopInfo(condBlock, bodyBlock, stepBlock, followBlock, preheader);
    }

    private void HoistLoopInvariant(LoopInfo loop) {
        HashSet<IrBasicBlock> loopBlocks = new HashSet<>();
        loopBlocks.add(loop.condBlock);
        loopBlocks.add(loop.bodyBlock);
        loopBlocks.add(loop.stepBlock);

        HashSet<Instr> hoistSet = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (IrBasicBlock block : new IrBasicBlock[]{loop.condBlock,
                loop.bodyBlock, loop.stepBlock}) {
                for (Instr instr : block.GetInstrList()) {
                    if (hoistSet.contains(instr)) {
                        continue;
                    }
                    if (CanHoist(instr, loopBlocks, hoistSet)) {
                        hoistSet.add(instr);
                        changed = true;
                    }
                }
            }
        }

        if (hoistSet.isEmpty()) {
            return;
        }

        ArrayList<Instr> hoistOrder = new ArrayList<>();
        for (IrBasicBlock block : new IrBasicBlock[]{loop.condBlock,
            loop.bodyBlock, loop.stepBlock}) {
            for (Instr instr : block.GetInstrList()) {
                if (hoistSet.contains(instr)) {
                    hoistOrder.add(instr);
                }
            }
        }

        for (Instr instr : hoistOrder) {
            IrBasicBlock from = instr.GetInBasicBlock();
            if (from == null) {
                continue;
            }
            Iterator<Instr> iterator = from.GetInstrList().iterator();
            while (iterator.hasNext()) {
                if (iterator.next() == instr) {
                    iterator.remove();
                    break;
                }
            }
            loop.preheader.AddInstrBeforeJump(instr);
        }
    }

    private boolean CanHoist(Instr instr, HashSet<IrBasicBlock> loopBlocks,
                             HashSet<Instr> hoistSet) {
        if (!(instr instanceof AluInstr || instr instanceof CompareInstr ||
            instr instanceof ExtendInstr || instr instanceof TruncInstr ||
            instr instanceof GepInstr)) {
            return false;
        }
        if (instr instanceof BranchInstr || instr instanceof JumpInstr ||
            instr instanceof ReturnInstr || instr instanceof PhiInstr) {
            return false;
        }
        if (!instr.DefValue()) {
            return false;
        }
        if (instr instanceof AluInstr aluInstr) {
            if (aluInstr.GetAluOp() == AluInstr.AluType.SDIV ||
                aluInstr.GetAluOp() == AluInstr.AluType.SREM) {
                IrValue divisor = aluInstr.GetValueR();
                if (!(divisor instanceof IrConstantInt c) || c.GetValue() == 0) {
                    return false;
                }
            }
        }
        for (IrUse use : instr.GetUseList()) {
            if (use.GetUser() instanceof PhiInstr) {
                return false;
            }
        }
        for (IrValue operand : instr.GetUseValueList()) {
            if (operand instanceof IrConstant) {
                continue;
            }
            if (operand instanceof Instr opInstr &&
                loopBlocks.contains(opInstr.GetInBasicBlock())) {
                if (!hoistSet.contains(opInstr)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static class LoopInfo {
        private final IrBasicBlock condBlock;
        private final IrBasicBlock bodyBlock;
        private final IrBasicBlock stepBlock;
        private final IrBasicBlock followBlock;
        private final IrBasicBlock preheader;

        private LoopInfo(IrBasicBlock condBlock, IrBasicBlock bodyBlock,
                         IrBasicBlock stepBlock, IrBasicBlock followBlock,
                         IrBasicBlock preheader) {
            this.condBlock = condBlock;
            this.bodyBlock = bodyBlock;
            this.stepBlock = stepBlock;
            this.followBlock = followBlock;
            this.preheader = preheader;
        }
    }
}
