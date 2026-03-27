package backend;

import backend.mips.MipsModule;
import backend.mips.assembly.MipsAssembly;
import backend.mips.assembly.MipsBranch;
import backend.mips.assembly.MipsJump;
import backend.mips.assembly.MipsLabel;
import backend.mips.assembly.MipsLsu;
import backend.mips.assembly.fake.MarsMove;

import java.util.ArrayList;
import java.util.HashSet;

public class PeepHole {
    private final MipsModule mipsModule;
    private final ArrayList<MipsAssembly> textSegement;

    public PeepHole() {
        this.mipsModule = BackEnd.GetMipsModule();
        this.textSegement = this.mipsModule.GetTextSegment();
    }

    public void Peep() {
        boolean finished = false;
        while (!finished) {
            finished = true;
            finished &= this.PeepContinueSW();
            finished &= this.PeepRemoveRedundantMove();
            finished &= this.PeepRemoveRedundantJump();
            finished &= this.PeepLoadStoreMove();
        }
    }

    private boolean PeepContinueSW() {
        boolean finished = true;
        HashSet<MipsAssembly> removeSet = new HashSet<>();
        for (int i = 0; i < this.textSegement.size(); i++) {
            MipsAssembly nowInstr = this.textSegement.get(i);
            if (nowInstr instanceof MipsLsu nowLsu && nowLsu.IsStoreType()) {
                if (i == 0) {
                    continue;
                }
                MipsAssembly beforeInstr = this.textSegement.get(i - 1);
                // 对同一地址连续写
                if (beforeInstr instanceof MipsLsu beforeLsu && beforeLsu.IsStoreType()) {
                    if (nowLsu.GetTarget().equals(beforeLsu.GetTarget())) {
                        removeSet.add(beforeInstr);
                        finished = false;
                    }
                }
            }
        }

        for (MipsAssembly removeInstr : removeSet) {
            this.textSegement.remove(removeInstr);
        }

        return finished;
    }

    private boolean PeepRemoveRedundantMove() {
        boolean finished = true;
        for (int i = 0; i < this.textSegement.size(); i++) {
            MipsAssembly instr = this.textSegement.get(i);
            if (instr instanceof MarsMove moveInstr) {
                if (moveInstr.GetDst() == moveInstr.GetSrc()) {
                    this.textSegement.remove(i);
                    i--;
                    finished = false;
                }
            }
        }
        return finished;
    }

    private boolean PeepRemoveRedundantJump() {
        boolean finished = true;
        for (int i = 0; i + 1 < this.textSegement.size(); i++) {
            MipsAssembly instr = this.textSegement.get(i);
            MipsAssembly next = this.textSegement.get(i + 1);
            if (instr instanceof MipsJump jumpInstr &&
                jumpInstr.GetJumpType() == MipsJump.JumpType.J &&
                next instanceof MipsLabel labelInstr &&
                labelInstr.GetLabel().equals(jumpInstr.GetTargetLabel())) {
                this.textSegement.remove(i);
                i--;
                finished = false;
                continue;
            }
            if (instr instanceof MipsBranch branchInstr &&
                next instanceof MipsLabel labelInstr &&
                labelInstr.GetLabel().equals(branchInstr.GetLabel())) {
                this.textSegement.remove(i);
                i--;
                finished = false;
            }
        }
        return finished;
    }

    private boolean PeepLoadStoreMove() {
        boolean finished = true;
        for (int i = 0; i + 1 < this.textSegement.size(); i++) {
            MipsAssembly instr = this.textSegement.get(i);
            MipsAssembly next = this.textSegement.get(i + 1);
            if (!(instr instanceof MipsLsu first && next instanceof MipsLsu second)) {
                continue;
            }
            if (!first.GetTarget().equals(second.GetTarget())) {
                continue;
            }
            if (first.IsLoadType() && second.IsLoadType()) {
                MarsMove move = new MarsMove(second.GetRd(), first.GetRd());
                ReplaceInstrAt(i + 1, move);
                finished = false;
                continue;
            }
            if (first.IsStoreType() && second.IsLoadType()) {
                MarsMove move = new MarsMove(second.GetRd(), first.GetRd());
                ReplaceInstrAt(i + 1, move);
                finished = false;
                continue;
            }
            if (first.IsLoadType() && second.IsStoreType() &&
                first.GetRd() == second.GetRd()) {
                this.textSegement.remove(i + 1);
                i--;
                finished = false;
            }
        }
        return finished;
    }

    private void ReplaceInstrAt(int index, MipsAssembly newInstr) {
        int lastIndex = this.textSegement.size() - 1;
        if (lastIndex >= 0 && this.textSegement.get(lastIndex) == newInstr) {
            this.textSegement.remove(lastIndex);
        }
        this.textSegement.set(index, newInstr);
    }
}
