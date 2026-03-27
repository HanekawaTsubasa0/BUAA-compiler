package optimize;

public class ConstPropLoop extends Optimizer {
    private static final int MAX_ROUNDS = 8;

    @Override
    public void Optimize() {
        for (int i = 0; i < MAX_ROUNDS; i++) {
            String before = irModule.toString();
            new Sccp().Optimize();
            new ConstFuncEval().Optimize();
            new ConstMemoryProp().Optimize();
            new Sccp().Optimize();
            new RemoveDeadCode().Optimize();
            new CfgBuilder().Optimize();
            String after = irModule.toString();
            if (after.equals(before)) {
                break;
            }
        }
    }
}
