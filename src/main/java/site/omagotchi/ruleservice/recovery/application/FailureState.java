package site.omagotchi.ruleservice.recovery.application;

public record FailureState(
        boolean failing,
        long parkedCount
) {
    public static FailureState none(){
        return new FailureState(false, 0);
    }

    public FailureState parked(){
        return new FailureState(true, parkedCount() + 1);
    }

    public FailureState recovered(){
        return failing ? none() : this;
    }
}
