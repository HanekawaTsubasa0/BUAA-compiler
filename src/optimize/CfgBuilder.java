package optimize;

import midend.llvm.instr.BranchInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.JumpInstr;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;

import java.util.ArrayList;
import java.util.HashSet;

public class CfgBuilder extends Optimizer {
    @Override
    public void Optimize() {
        InitFunction();
        BuildCfg();
        BuildDominateRelationship();
        BuildDirectDominator();
        BuildDominateFrontier();
    }

    private void InitFunction() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            for (IrBasicBlock irBasicBlock : irFunction.GetBasicBlocks()) {
                irBasicBlock.ClearCfg();
            }
        }
    }

    private void BuildCfg() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            for (IrBasicBlock visitBlock : irFunction.GetBasicBlocks()) {
                for (Instr instr : visitBlock.GetInstrList()) {
                    if (instr instanceof JumpInstr jumpInstr) {
                        if (jumpInstr.GetUseValueList().isEmpty()) {
                            continue;
                        }
                        IrBasicBlock targetBlock = jumpInstr.GetTargetBlock();
                        visitBlock.AddNextBlock(targetBlock);
                        targetBlock.AddBeforeBlock(visitBlock);
                    } else if (instr instanceof BranchInstr branchInstr) {
                        if (branchInstr.GetUseValueList().size() < 3) {
                            continue;
                        }
                        IrBasicBlock trueBlock = branchInstr.GetTrueBlock();
                        IrBasicBlock falseBlock = branchInstr.GetFalseBlock();
                        visitBlock.AddNextBlock(trueBlock);
                        visitBlock.AddNextBlock(falseBlock);
                        trueBlock.AddBeforeBlock(visitBlock);
                        falseBlock.AddBeforeBlock(visitBlock);
                    }
                }
            }
        }
    }

    private void BuildDominateRelationship() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            ArrayList<IrBasicBlock> blockList = irFunction.GetBasicBlocks();
            for (IrBasicBlock deleteBlock : blockList) {
                HashSet<IrBasicBlock> visited = new HashSet<>();
                SearchDfs(blockList.get(0), deleteBlock, visited);
                for (IrBasicBlock visitBlock : blockList) {
                    if (!visited.contains(visitBlock)) {
                        visitBlock.AddDominator(deleteBlock);
                    }
                }
            }
        }
    }

    private void SearchDfs(IrBasicBlock visitBlock, IrBasicBlock deleteBlock,
                           HashSet<IrBasicBlock> visited) {
        if (visitBlock == deleteBlock) {
            return;
        }

        visited.add(visitBlock);
        for (IrBasicBlock nextBlock : visitBlock.GetNextBlocks()) {
            if (!visited.contains(nextBlock) && nextBlock != deleteBlock) {
                SearchDfs(nextBlock, deleteBlock, visited);
            }
        }
    }

    private void BuildDirectDominator() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            for (IrBasicBlock visitBlock : irFunction.GetBasicBlocks()) {
                for (IrBasicBlock dominator : visitBlock.GetDominatorBlocks()) {
                    HashSet<IrBasicBlock> sharedDominators =
                        new HashSet<>(visitBlock.GetDominatorBlocks());
                    sharedDominators.retainAll(dominator.GetDominatorBlocks());

                    HashSet<IrBasicBlock> diffDominators =
                        new HashSet<>(visitBlock.GetDominatorBlocks());
                    diffDominators.removeAll(sharedDominators);
                    if (diffDominators.size() == 1 && diffDominators.contains(visitBlock)) {
                        visitBlock.AddDirectDominatorRelationship(dominator);
                        break;
                    }
                }
            }
        }
    }

    private void BuildDominateFrontier() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            for (IrBasicBlock visitBlock : irFunction.GetBasicBlocks()) {
                ArrayList<IrBasicBlock> nextBlocksBlocks = visitBlock.GetNextBlocks();
                for (IrBasicBlock nextBlock : nextBlocksBlocks) {
                    IrBasicBlock currentBlock = visitBlock;
                    while (!nextBlock.GetDominatorBlocks().contains(currentBlock) ||
                        currentBlock == nextBlock) {
                        currentBlock.AddDominateFrontier(nextBlock);
                        currentBlock = currentBlock.GetDirectDominator();
                        if (currentBlock == null) {
                            break;
                        }
                    }
                }
            }
        }
    }
}
