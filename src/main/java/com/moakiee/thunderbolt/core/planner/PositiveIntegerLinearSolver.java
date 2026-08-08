package com.moakiee.thunderbolt.core.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class PositiveIntegerLinearSolver {
   public static PositiveIntegerLinearSolver.Result solve(int variableCount, List<PositiveIntegerLinearSolver.Constraint> constraints) {
      if (variableCount > 0 && constraints != null) {
         ArrayList<PositiveIntegerLinearSolver.ExactConstraint> rows = new ArrayList<>(constraints.size());

         for (PositiveIntegerLinearSolver.Constraint constraint : constraints) {
            if (constraint == null) {
               return result(PositiveIntegerLinearSolver.Status.INVALID_INPUT);
            }

            if (constraint.minimum() < 0L) {
               return result(PositiveIntegerLinearSolver.Status.INVALID_INPUT);
            }

            long[] coefficients = constraint.coefficients();
            if (coefficients.length != variableCount) {
               return result(PositiveIntegerLinearSolver.Status.INVALID_INPUT);
            }

            BigInteger[] exact = new BigInteger[variableCount];

            for (int i = 0; i < variableCount; i++) {
               exact[i] = BigInteger.valueOf(coefficients[i]);
            }

            rows.add(new PositiveIntegerLinearSolver.ExactConstraint(exact, BigInteger.valueOf(constraint.minimum())));
         }

         if (rows.isEmpty()) {
            long[] ones = new long[variableCount];
            Arrays.fill(ones, 1L);
            return new PositiveIntegerLinearSolver.Result(PositiveIntegerLinearSolver.Status.SOLVED, ones);
         } else {
            try {
               return solveExact(variableCount, rows);
            } catch (RuntimeException var8) {
               return result(PositiveIntegerLinearSolver.Status.INTERNAL_ERROR);
            }
         }
      } else {
         return result(PositiveIntegerLinearSolver.Status.INVALID_INPUT);
      }
   }

   private static PositiveIntegerLinearSolver.Result solveExact(int variableCount, List<PositiveIntegerLinearSolver.ExactConstraint> constraints) {
      int rowCount = constraints.size();
      BigInteger[] shiftedMinimums = new BigInteger[rowCount];
      int artificialCount = 0;

      for (int row = 0; row < rowCount; row++) {
         PositiveIntegerLinearSolver.ExactConstraint constraint = constraints.get(row);
         BigInteger atOnes = BigInteger.ZERO;

         for (BigInteger coefficient : constraint.coefficients()) {
            atOnes = atOnes.add(coefficient);
         }

         shiftedMinimums[row] = constraint.minimum().subtract(atOnes);
         if (shiftedMinimums[row].signum() > 0) {
            artificialCount++;
         }
      }

      int auxiliaryOffset = variableCount;
      int artificialOffset = variableCount + rowCount;
      int totalVariables = artificialOffset + artificialCount;
      PositiveIntegerLinearSolver.Rational[][] tableau = new PositiveIntegerLinearSolver.Rational[rowCount][totalVariables + 1];

      for (PositiveIntegerLinearSolver.Rational[] row : tableau) {
         Arrays.fill(row, PositiveIntegerLinearSolver.Rational.ZERO);
      }

      int[] basis = new int[rowCount];
      PositiveIntegerLinearSolver.Rational[] costs = new PositiveIntegerLinearSolver.Rational[totalVariables];
      Arrays.fill(costs, PositiveIntegerLinearSolver.Rational.ZERO);
      int nextArtificial = artificialOffset;

      for (int row = 0; row < rowCount; row++) {
         PositiveIntegerLinearSolver.ExactConstraint constraint = constraints.get(row);
         boolean needsArtificial = shiftedMinimums[row].signum() > 0;
         BigInteger sign = needsArtificial ? BigInteger.ONE : BigInteger.ONE.negate();

         for (int column = 0; column < variableCount; column++) {
            tableau[row][column] = PositiveIntegerLinearSolver.Rational.of(constraint.coefficients()[column].multiply(sign));
         }

         tableau[row][auxiliaryOffset + row] = needsArtificial ? PositiveIntegerLinearSolver.Rational.NEGATIVE_ONE : PositiveIntegerLinearSolver.Rational.ONE;
         tableau[row][totalVariables] = PositiveIntegerLinearSolver.Rational.of(shiftedMinimums[row].multiply(sign));
         if (needsArtificial) {
            tableau[row][nextArtificial] = PositiveIntegerLinearSolver.Rational.ONE;
            basis[row] = nextArtificial;
            costs[nextArtificial] = PositiveIntegerLinearSolver.Rational.NEGATIVE_ONE;
            nextArtificial++;
         } else {
            basis[row] = auxiliaryOffset + row;
         }
      }

      PositiveIntegerLinearSolver.SimplexStatus simplexStatus = maximize(tableau, basis, costs, totalVariables);
      if (simplexStatus != PositiveIntegerLinearSolver.SimplexStatus.OPTIMAL) {
         return result(PositiveIntegerLinearSolver.Status.INTERNAL_ERROR);
      } else {
         PositiveIntegerLinearSolver.Rational objective = PositiveIntegerLinearSolver.Rational.ZERO;

         for (int row = 0; row < rowCount; row++) {
            objective = objective.add(costs[basis[row]].multiply(tableau[row][totalVariables]));
         }

         if (objective.signum() < 0) {
            return result(PositiveIntegerLinearSolver.Status.INFEASIBLE);
         } else if (objective.signum() > 0) {
            return result(PositiveIntegerLinearSolver.Status.INTERNAL_ERROR);
         } else {
            PositiveIntegerLinearSolver.Rational[] positiveRational = new PositiveIntegerLinearSolver.Rational[variableCount];
            Arrays.fill(positiveRational, PositiveIntegerLinearSolver.Rational.ONE);

            for (int row = 0; row < rowCount; row++) {
               int basic = basis[row];
               if (basic < variableCount) {
                  positiveRational[basic] = tableau[row][totalVariables].add(PositiveIntegerLinearSolver.Rational.ONE);
               }
            }

            BigInteger commonDenominator = BigInteger.ONE;

            for (PositiveIntegerLinearSolver.Rational value : positiveRational) {
               commonDenominator = lcm(commonDenominator, value.denominator());
            }

            BigInteger[] integer = new BigInteger[variableCount];

            for (int i = 0; i < variableCount; i++) {
               integer[i] = positiveRational[i].numerator().multiply(commonDenominator.divide(positiveRational[i].denominator()));
            }

            minimizeCoordinates(integer, constraints);
            if (!isFeasible(integer, constraints)) {
               return result(PositiveIntegerLinearSolver.Status.INTERNAL_ERROR);
            } else {
               long[] result = new long[variableCount];

               for (int i = 0; i < variableCount; i++) {
                  if (integer[i].signum() <= 0 || integer[i].compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                     return result(PositiveIntegerLinearSolver.Status.COEFFICIENT_OVERFLOW);
                  }

                  result[i] = integer[i].longValueExact();
               }

               return new PositiveIntegerLinearSolver.Result(PositiveIntegerLinearSolver.Status.SOLVED, result);
            }
         }
      }
   }

   private static PositiveIntegerLinearSolver.SimplexStatus maximize(
      PositiveIntegerLinearSolver.Rational[][] tableau, int[] basis, PositiveIntegerLinearSolver.Rational[] costs, int variableCount
   ) {
      int rowCount = tableau.length;

      while (true) {
         int entering = -1;

         for (int column = 0; column < variableCount; column++) {
            PositiveIntegerLinearSolver.Rational reduced = costs[column];

            for (int row = 0; row < rowCount; row++) {
               reduced = reduced.subtract(costs[basis[row]].multiply(tableau[row][column]));
            }

            if (reduced.signum() > 0) {
               entering = column;
               break;
            }
         }

         if (entering < 0) {
            return PositiveIntegerLinearSolver.SimplexStatus.OPTIMAL;
         }

         int leaving = -1;
         PositiveIntegerLinearSolver.Rational bestRatio = null;

         for (int row = 0; row < rowCount; row++) {
            PositiveIntegerLinearSolver.Rational direction = tableau[row][entering];
            if (direction.signum() > 0) {
               PositiveIntegerLinearSolver.Rational ratio = tableau[row][variableCount].divide(direction);
               if (leaving < 0 || ratio.compareTo(bestRatio) < 0 || ratio.equals(bestRatio) && basis[row] < basis[leaving]) {
                  leaving = row;
                  bestRatio = ratio;
               }
            }
         }

         if (leaving < 0) {
            return PositiveIntegerLinearSolver.SimplexStatus.UNBOUNDED;
         }

         pivot(tableau, basis, leaving, entering, variableCount);
      }
   }

   private static void pivot(PositiveIntegerLinearSolver.Rational[][] tableau, int[] basis, int pivotRow, int pivotColumn, int variableCount) {
      PositiveIntegerLinearSolver.Rational pivot = tableau[pivotRow][pivotColumn];

      for (int column = 0; column <= variableCount; column++) {
         tableau[pivotRow][column] = tableau[pivotRow][column].divide(pivot);
      }

      for (int row = 0; row < tableau.length; row++) {
         if (row != pivotRow) {
            PositiveIntegerLinearSolver.Rational factor = tableau[row][pivotColumn];
            if (factor.signum() != 0) {
               for (int column = 0; column <= variableCount; column++) {
                  tableau[row][column] = tableau[row][column].subtract(factor.multiply(tableau[pivotRow][column]));
               }
            }
         }
      }

      basis[pivotRow] = pivotColumn;
   }

   private static void minimizeCoordinates(BigInteger[] values, List<PositiveIntegerLinearSolver.ExactConstraint> constraints) {
      for (int variable = 0; variable < values.length; variable++) {
         BigInteger lower = BigInteger.ONE;

         for (PositiveIntegerLinearSolver.ExactConstraint constraint : constraints) {
            BigInteger coefficient = constraint.coefficients()[variable];
            if (coefficient.signum() > 0) {
               BigInteger other = dot(constraint.coefficients(), values).subtract(coefficient.multiply(values[variable]));
               BigInteger needed = ceilDivide(constraint.minimum().subtract(other), coefficient);
               if (needed.compareTo(lower) > 0) {
                  lower = needed;
               }
            }
         }

         if (lower.compareTo(values[variable]) < 0) {
            values[variable] = lower.max(BigInteger.ONE);
         }
      }
   }

   private static boolean isFeasible(BigInteger[] values, List<PositiveIntegerLinearSolver.ExactConstraint> constraints) {
      for (BigInteger value : values) {
         if (value.signum() <= 0) {
            return false;
         }
      }

      for (PositiveIntegerLinearSolver.ExactConstraint constraint : constraints) {
         if (dot(constraint.coefficients(), values).compareTo(constraint.minimum()) < 0) {
            return false;
         }
      }

      return true;
   }

   private static BigInteger dot(BigInteger[] coefficients, BigInteger[] values) {
      BigInteger result = BigInteger.ZERO;

      for (int i = 0; i < coefficients.length; i++) {
         result = result.add(coefficients[i].multiply(values[i]));
      }

      return result;
   }

   private static BigInteger ceilDivide(BigInteger numerator, BigInteger positiveDenominator) {
      BigInteger[] divided = numerator.divideAndRemainder(positiveDenominator);
      return divided[1].signum() != 0 && numerator.signum() > 0 ? divided[0].add(BigInteger.ONE) : divided[0];
   }

   private static BigInteger lcm(BigInteger left, BigInteger right) {
      return left.signum() != 0 && right.signum() != 0 ? left.divide(left.gcd(right)).multiply(right).abs() : BigInteger.ZERO;
   }

   private static PositiveIntegerLinearSolver.Result result(PositiveIntegerLinearSolver.Status status) {
      return new PositiveIntegerLinearSolver.Result(status, new long[0]);
   }

   private PositiveIntegerLinearSolver() {
   }

   public static record Constraint(long[] coefficients, long minimum) {
      public Constraint(long[] coefficients, long minimum) {
         Objects.requireNonNull(coefficients, "coefficients");
         coefficients = (long[])coefficients.clone();
         this.coefficients = coefficients;
         this.minimum = minimum;
      }

      public long[] coefficients() {
         return (long[])this.coefficients.clone();
      }
   }

   private static record ExactConstraint(BigInteger[] coefficients, BigInteger minimum) {
   }

   private static final class Rational implements Comparable<PositiveIntegerLinearSolver.Rational> {
      private static final PositiveIntegerLinearSolver.Rational ZERO = new PositiveIntegerLinearSolver.Rational(BigInteger.ZERO, BigInteger.ONE);
      private static final PositiveIntegerLinearSolver.Rational ONE = new PositiveIntegerLinearSolver.Rational(BigInteger.ONE, BigInteger.ONE);
      private static final PositiveIntegerLinearSolver.Rational NEGATIVE_ONE = new PositiveIntegerLinearSolver.Rational(BigInteger.ONE.negate(), BigInteger.ONE);
      private final BigInteger numerator;
      private final BigInteger denominator;

      private Rational(BigInteger numerator, BigInteger denominator) {
         if (denominator.signum() == 0) {
            throw new ArithmeticException("zero denominator");
         } else {
            if (denominator.signum() < 0) {
               numerator = numerator.negate();
               denominator = denominator.negate();
            }

            BigInteger gcd = numerator.gcd(denominator);
            this.numerator = numerator.divide(gcd);
            this.denominator = denominator.divide(gcd);
         }
      }

      static PositiveIntegerLinearSolver.Rational of(BigInteger value) {
         if (value.signum() == 0) {
            return ZERO;
         } else if (value.equals(BigInteger.ONE)) {
            return ONE;
         } else {
            return value.equals(BigInteger.ONE.negate()) ? NEGATIVE_ONE : new PositiveIntegerLinearSolver.Rational(value, BigInteger.ONE);
         }
      }

      PositiveIntegerLinearSolver.Rational add(PositiveIntegerLinearSolver.Rational other) {
         return new PositiveIntegerLinearSolver.Rational(
            this.numerator.multiply(other.denominator).add(other.numerator.multiply(this.denominator)), this.denominator.multiply(other.denominator)
         );
      }

      PositiveIntegerLinearSolver.Rational subtract(PositiveIntegerLinearSolver.Rational other) {
         return new PositiveIntegerLinearSolver.Rational(
            this.numerator.multiply(other.denominator).subtract(other.numerator.multiply(this.denominator)), this.denominator.multiply(other.denominator)
         );
      }

      PositiveIntegerLinearSolver.Rational multiply(PositiveIntegerLinearSolver.Rational other) {
         return new PositiveIntegerLinearSolver.Rational(this.numerator.multiply(other.numerator), this.denominator.multiply(other.denominator));
      }

      PositiveIntegerLinearSolver.Rational divide(PositiveIntegerLinearSolver.Rational other) {
         return new PositiveIntegerLinearSolver.Rational(this.numerator.multiply(other.denominator), this.denominator.multiply(other.numerator));
      }

      int signum() {
         return this.numerator.signum();
      }

      BigInteger numerator() {
         return this.numerator;
      }

      BigInteger denominator() {
         return this.denominator;
      }

      public int compareTo(PositiveIntegerLinearSolver.Rational other) {
         return this.numerator.multiply(other.denominator).compareTo(other.numerator.multiply(this.denominator));
      }

      @Override
      public boolean equals(Object obj) {
         if (obj instanceof PositiveIntegerLinearSolver.Rational other && this.numerator.equals(other.numerator) && this.denominator.equals(other.denominator)) {
            return true;
         }

         return false;
      }

      @Override
      public int hashCode() {
         return 31 * this.numerator.hashCode() + this.denominator.hashCode();
      }
   }

   public static record Result(PositiveIntegerLinearSolver.Status status, long[] coefficients) {
      public Result(PositiveIntegerLinearSolver.Status status, long[] coefficients) {
         Objects.requireNonNull(status, "status");
         coefficients = coefficients == null ? new long[0] : (long[])coefficients.clone();
         if (status == PositiveIntegerLinearSolver.Status.SOLVED && coefficients.length == 0) {
            throw new IllegalArgumentException("a solved result requires coefficients");
         } else {
            this.status = status;
            this.coefficients = coefficients;
         }
      }

      public long[] coefficients() {
         return (long[])this.coefficients.clone();
      }

      public boolean solved() {
         return this.status == PositiveIntegerLinearSolver.Status.SOLVED;
      }
   }

   private static enum SimplexStatus {
      OPTIMAL,
      UNBOUNDED;
   }

   public static enum Status {
      SOLVED,
      INFEASIBLE,
      COEFFICIENT_OVERFLOW,
      INVALID_INPUT,
      INTERNAL_ERROR;
   }
}
