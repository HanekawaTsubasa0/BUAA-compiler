package optimize;

import midend.llvm.instr.BranchInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.JumpInstr;
import midend.llvm.instr.ReturnInstr;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;

import java.util.HashSet;
import java.util.Iterator;

public class RemoveUnReachCode extends Optimizer {
    @Override
    public void Optimize() {
        RemoveUselessJump();
        RemoveUselessBlock();
    }

    private void RemoveUselessJump() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            for (IrBasicBlock irBasicBlock : irFunction.GetBasicBlocks()) {
                boolean hasJump = false;
                Iterator<Instr> iterator = irBasicBlock.GetInstrList().iterator();
                while (iterator.hasNext()) {
                    Instr instr = iterator.next();
                    if (hasJump) {
                        instr.RemoveAllValueUse();
                        iterator.remove();
                        continue;
                    }

                    if (instr instanceof JumpInstr || instr instanceof BranchInstr ||
                        instr instanceof ReturnInstr) {
                        hasJump = true;
                    }
                }
            }
        }
    }

    private void RemoveUselessBlock() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            IrBasicBlock entryBlock = irFunction.GetBasicBlocks().get(0);
            HashSet<IrBasicBlock> referenced = GetReferencedBlocks(irFunction);
            HashSet<IrBasicBlock> visited = new HashSet<>();
            DfsBlock(entryBlock, visited);
            irFunction.GetBasicBlocks().removeIf(block ->
                !visited.contains(block) && !referenced.contains(block));
        }
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

    private void DfsBlock(IrBasicBlock block, HashSet<IrBasicBlock> visited) {
        if (visited.contains(block)) {
            return;
        }

        visited.add(block);
        Instr instr = block.GetLastInstr();
        if (instr instanceof ReturnInstr) {
            return;
        }
        if (instr instanceof JumpInstr jumpInstr) {
            if (jumpInstr.GetUseValueList().isEmpty()) {
                return;
            }
            DfsBlock(jumpInstr.GetTargetBlock(), visited);
        } else if (instr instanceof BranchInstr branchInstr) {
            if (branchInstr.GetUseValueList().size() < 3) {
                return;
            }
            DfsBlock(branchInstr.GetTrueBlock(), visited);
            DfsBlock(branchInstr.GetFalseBlock(), visited);
        }
    }
}
