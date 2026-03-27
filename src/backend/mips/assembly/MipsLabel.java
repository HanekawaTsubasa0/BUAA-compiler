package backend.mips.assembly;

public class MipsLabel extends MipsAssembly {
    private final String label;

    public MipsLabel(String label) {
        super(MipsType.LABEL);
        this.label = label;
    }

    public String GetLabel() {
        return this.label;
    }

    @Override
    public String toString() {
        return "\n" + this.label + ":";
    }
}
