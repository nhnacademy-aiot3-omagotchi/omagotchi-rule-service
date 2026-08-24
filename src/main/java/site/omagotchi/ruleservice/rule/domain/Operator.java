package site.omagotchi.ruleservice.rule.domain;

/**
 * 비교 연산자. <br/>
 * EQ(같음), NEQ(같지 않음)은 double에서 신뢰하기 어렵기 때문에 사용하지않음
 */
public enum Operator {
    /** 초과 */
    GT{
        @Override
        public boolean matches(double value, double threshold) {
            return value > threshold;
        }
    },
    /** 이상 */
    GTE{
        @Override
        public boolean matches(double value, double threshold) {
            return value >= threshold;
        }
    },

    /** 미만 */
    LT{
        @Override
        public boolean matches(double value, double threshold) {
            return value < threshold;
        }
    },

    /** 이하 */
    LTE{
        @Override
        public boolean matches(double value, double threshold) {
            return value <= threshold;
        }
    };

    //추상 메서드로 구현해야 각 상수에 구현이 강제됨.
    /** 현재 룰의 임계값(threshold)과 비교할 센서 값(value)를 비교 각 비교 상수(operator)에 따라 다르게 동작*/
    public abstract boolean matches(double value, double threshold);


    /**RuleResponse -> Threshold 로 변환할경우 String -> enum 변환 */
    public static Operator from(String raw){
        if(raw == null){
            throw new IllegalArgumentException("operator가 null입니다");
        }
        try{
            return valueOf(raw);
        }catch (IllegalArgumentException e){
            throw new IllegalArgumentException("지원하지않는 operator입니다.");
        }
    }
}
