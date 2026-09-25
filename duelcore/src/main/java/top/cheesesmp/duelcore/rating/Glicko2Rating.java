package top.cheesesmp.duelcore.rating;

import top.cheesesmp.duelcore.profile.KitStats;

/**
 * Glicko-2 (Glickman, 2012) where every match is its own rating period.
 * Ratings are stored on the Glicko-1 scale (default 1000 / RD 350 / σ 0.06).
 */
public final class Glicko2Rating implements RatingSystem {

    private static final double SCALE = 173.7178;
    private static final double EPSILON = 0.000001;

    private final double tau;
    private final double base;
    private final double minRd;
    private final double maxRd;
    private final double floor;

    public Glicko2Rating(double tau, double base, double minRd, double maxRd, double floor) {
        this.tau = tau;
        this.base = base;
        this.minRd = minRd;
        this.maxRd = maxRd;
        this.floor = floor;
    }

    @Override
    public Result rate(KitStats a, KitStats b, double scoreA) {
        Side na = update(a, b, scoreA);
        Side nb = update(b, a, 1 - scoreA);
        return new Result(na, nb);
    }

    private Side update(KitStats self, KitStats opp, double score) {
        double[] r = period(self.rating, self.rd, self.volatility,
            new double[] {opp.rating}, new double[] {opp.rd}, new double[] {score});
        return new Side(Math.max(floor, r[0]), Math.clamp(r[1], minRd, maxRd), r[2]);
    }

    /**
     * One Glicko-2 rating period against several opponents.
     *
     * @return {rating, rd, volatility} on the Glicko-1 scale
     */
    double[] period(double rating, double rd, double sigma, double[] oppRating, double[] oppRd, double[] scores) {
        double mu = (rating - base) / SCALE;
        double phi = rd / SCALE;
        double vInv = 0;
        double deltaSum = 0;
        for (int j = 0; j < oppRating.length; j++) {
            double muJ = (oppRating[j] - base) / SCALE;
            double phiJ = oppRd[j] / SCALE;
            double g = 1.0 / Math.sqrt(1.0 + 3.0 * phiJ * phiJ / (Math.PI * Math.PI));
            double e = 1.0 / (1.0 + Math.exp(-g * (mu - muJ)));
            vInv += g * g * e * (1 - e);
            deltaSum += g * (scores[j] - e);
        }
        double v = 1.0 / vInv;
        double delta = v * deltaSum;

        double newSigma = volatility(delta, phi, v, sigma);
        double phiStar = Math.sqrt(phi * phi + newSigma * newSigma);
        double newPhi = 1.0 / Math.sqrt(1.0 / (phiStar * phiStar) + 1.0 / v);
        double newMu = mu + newPhi * newPhi * deltaSum;
        return new double[] {newMu * SCALE + base, newPhi * SCALE, newSigma};
    }

    private double volatility(double delta, double phi, double v, double sigma) {
        double a = Math.log(sigma * sigma);
        double deltaSq = delta * delta;
        double phiSq = phi * phi;
        double bigA = a;
        double bigB;
        if (deltaSq > phiSq + v) {
            bigB = Math.log(deltaSq - phiSq - v);
        } else {
            int k = 1;
            while (f(a - k * tau, deltaSq, phiSq, v, a) < 0) k++;
            bigB = a - k * tau;
        }
        double fA = f(bigA, deltaSq, phiSq, v, a);
        double fB = f(bigB, deltaSq, phiSq, v, a);
        int guard = 0;
        while (Math.abs(bigB - bigA) > EPSILON && guard++ < 100) {
            double bigC = bigA + (bigA - bigB) * fA / (fB - fA);
            double fC = f(bigC, deltaSq, phiSq, v, a);
            if (fC * fB <= 0) {
                bigA = bigB;
                fA = fB;
            } else {
                fA = fA / 2.0;
            }
            bigB = bigC;
            fB = fC;
        }
        return Math.exp(bigA / 2.0);
    }

    private double f(double x, double deltaSq, double phiSq, double v, double a) {
        double ex = Math.exp(x);
        double num = ex * (deltaSq - phiSq - v - ex);
        double den = 2.0 * Math.pow(phiSq + v + ex, 2);
        return num / den - (x - a) / (tau * tau);
    }
}
