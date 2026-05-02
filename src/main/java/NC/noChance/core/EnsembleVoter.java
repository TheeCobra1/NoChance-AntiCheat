package NC.noChance.core;

public final class EnsembleVoter {

    private EnsembleVoter() {}

    public static Vote vote(double entropy, double consistency, double correlation,
                            double anomaly, double fingerprint,
                            DetectionEngine.SuspicionLevel suspicion, ViolationType type) {

        double[] scores = {entropy, consistency, correlation, anomaly, fingerprint};
        double[] weights = {0.22, 0.28, 0.22, 0.20, 0.08};

        if (suspicion == DetectionEngine.SuspicionLevel.CONFIRMED) {
            weights = new double[]{0.20, 0.30, 0.26, 0.18, 0.06};
        } else if (suspicion == DetectionEngine.SuspicionLevel.HIGH) {
            weights = new double[]{0.22, 0.28, 0.24, 0.18, 0.08};
        } else if (suspicion == DetectionEngine.SuspicionLevel.CLEAN) {
            weights = new double[]{0.24, 0.24, 0.18, 0.24, 0.10};
        }

        double weightedScore = 0.0;
        for (int i = 0; i < scores.length; i++) {
            weightedScore += scores[i] * weights[i];
        }

        int votesForCheat = 0;
        int totalVotes = 0;

        if (entropy > 0.60) { votesForCheat++; totalVotes++; }
        if (consistency > 0.60) { votesForCheat++; totalVotes++; }
        if (correlation > 0.55) { votesForCheat++; totalVotes++; }
        if (anomaly > 0.60) { votesForCheat++; totalVotes++; }
        if (fingerprint > 0.62) { votesForCheat++; totalVotes++; }

        double voteRatio = totalVotes > 0 ? (double) votesForCheat / totalVotes : 0.0;

        double finalConfidence = (weightedScore * 0.60) + (voteRatio * 0.40);

        boolean shouldFlag = votesForCheat >= 3 && finalConfidence >= 0.68;

        String detectionMethod = dominantMethod(entropy, consistency, correlation, anomaly, fingerprint);

        return new Vote(shouldFlag, finalConfidence, detectionMethod, votesForCheat, totalVotes);
    }

    private static String dominantMethod(double entropy, double consistency,
                                         double correlation, double anomaly, double fingerprint) {
        double[] scores = {entropy, consistency, correlation, anomaly, fingerprint};
        String[] names = {"Entropy", "Consistency", "Correlation", "Anomaly", "Fingerprint"};
        int maxIdx = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[maxIdx]) {
                maxIdx = i;
            }
        }
        return names[maxIdx];
    }

    public static final class Vote {
        public final boolean shouldFlag;
        public final double confidence;
        public final String detectionMethod;
        public final int votesForCheat;
        public final int totalVotes;

        Vote(boolean shouldFlag, double confidence, String detectionMethod,
             int votesForCheat, int totalVotes) {
            this.shouldFlag = shouldFlag;
            this.confidence = confidence;
            this.detectionMethod = detectionMethod;
            this.votesForCheat = votesForCheat;
            this.totalVotes = totalVotes;
        }
    }
}
