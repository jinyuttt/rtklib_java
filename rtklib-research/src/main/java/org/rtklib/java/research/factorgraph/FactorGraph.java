package org.rtklib.java.research.factorgraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.research.common.MatrixOps;

/**
 * 因子图（research模块因子图框架）。
 *
 * <p>管理变量节点和因子节点，提供高斯-牛顿/Levenberg-Marquardt优化。</p>
 */
public class FactorGraph {
    private final Map<String, Variable> variables;
    private final List<Factor> factors;

    public FactorGraph() {
        this.variables = new LinkedHashMap<>();
        this.factors = new ArrayList<>();
    }

    public void addVariable(Variable var) {
        variables.put(var.name, var);
    }

    public Variable getVariable(String name) {
        return variables.get(name);
    }

    public void addFactor(Factor factor) {
        factors.add(factor);
    }

    public List<Factor> getFactors() {
        return Collections.unmodifiableList(factors);
    }

    public Map<String, Variable> getVariables() {
        return Collections.unmodifiableMap(variables);
    }

    public void removeVariable(String name) {
        variables.remove(name);
    }

    public void removeFactor(Factor factor) {
        factors.remove(factor);
    }

    public int totalVariableDimension() {
        int dim = 0;
        for (Variable v : variables.values()) {
            if (!v.fixed) dim += v.dimension;
        }
        return dim;
    }

    public int totalResidualDimension() {
        int dim = 0;
        for (Factor f : factors) dim += f.residualDimension();
        return dim;
    }

    public double totalError() {
        double err = 0;
        for (Factor f : factors) err += f.error();
        return err;
    }

    public OptimizationResult optimize(int maxIter, double tol, boolean useDamping) {
        OptimizationResult result = new OptimizationResult();
        result.converged = false;
        result.iterations = 0;
        result.initialError = totalError();

        double lambda = 1e-3;
        double prevError = result.initialError;

        for (int iter = 0; iter < maxIter; iter++) {
            int n = totalVariableDimension();
            if (n == 0) break;

            SimpleMatrix H = new SimpleMatrix(n, n);
            SimpleMatrix b = new SimpleMatrix(n, 1);

            int rowOff = 0;
            List<String> varOrder = new ArrayList<>(variables.keySet());
            List<Integer> varOffsets = new ArrayList<>();
            int off = 0;
            for (String vName : varOrder) {
                Variable v = variables.get(vName);
                varOffsets.add(off);
                if (!v.fixed) off += v.dimension;
            }

            for (Factor f : factors) {
                SimpleMatrix Rinv = f.noiseCovariance().invert();
                SimpleMatrix r = f.residual();

                for (int j = 0; j < f.connectedVariables.size(); j++) {
                    Variable vj = f.connectedVariables.get(j);
                    int jIdx = varOrder.indexOf(vj.name);
                    if (jIdx < 0 || vj.fixed) continue;
                    int jOff = varOffsets.get(jIdx);
                    SimpleMatrix Jj = f.jacobian(j);

                    for (int i = 0; i < f.connectedVariables.size(); i++) {
                        Variable vi = f.connectedVariables.get(i);
                        int iIdx = varOrder.indexOf(vi.name);
                        if (iIdx < 0 || vi.fixed) continue;
                        int iOff = varOffsets.get(iIdx);
                        SimpleMatrix Ji = f.jacobian(i);
                        SimpleMatrix block = Ji.transpose().mult(Rinv).mult(Jj);
                        for (int rr = 0; rr < vi.dimension; rr++) {
                            for (int cc = 0; cc < vj.dimension; cc++) {
                                H.set(iOff + rr, jOff + cc, H.get(iOff + rr, jOff + cc) + block.get(rr, cc));
                            }
                        }
                    }

                    SimpleMatrix bj = Jj.transpose().mult(Rinv).mult(r);
                    for (int rr = 0; rr < vj.dimension; rr++) {
                        b.set(jOff + rr, 0, b.get(jOff + rr, 0) + bj.get(rr, 0));
                    }
                }
            }

            if (useDamping) {
                for (int i = 0; i < n; i++) {
                    H.set(i, i, H.get(i, i) * (1.0 + lambda));
                }
            }

            SimpleMatrix dx;
            try {
                dx = H.solve(b);
            } catch (Exception e) {
                result.converged = false;
                result.iterations = iter + 1;
                result.finalError = totalError();
                return result;
            }

            off = 0;
            for (String vName : varOrder) {
                Variable v = variables.get(vName);
                if (!v.fixed) {
                    SimpleMatrix delta = dx.extractMatrix(off, off + v.dimension, 0, 1);
                    v.setValue(v.value.plus(delta));
                    off += v.dimension;
                }
            }

            double currError = totalError();
            result.iterations = iter + 1;

            if (Math.abs(prevError - currError) < tol) {
                result.converged = true;
                result.finalError = currError;
                break;
            }

            if (useDamping) {
                if (currError < prevError) {
                    lambda *= 0.5;
                } else {
                    lambda *= 2.0;
                }
            }

            prevError = currError;
        }

        result.finalError = totalError();

        int n = totalVariableDimension();
        if (n > 0) {
            SimpleMatrix H = new SimpleMatrix(n, n);
            List<String> varOrder = new ArrayList<>(variables.keySet());
            List<Integer> varOffsets = new ArrayList<>();
            int off2 = 0;
            for (String vName : varOrder) {
                Variable v = variables.get(vName);
                varOffsets.add(off2);
                if (!v.fixed) off2 += v.dimension;
            }
            for (Factor f : factors) {
                SimpleMatrix Rinv = f.noiseCovariance().invert();
                for (int j = 0; j < f.connectedVariables.size(); j++) {
                    Variable vj = f.connectedVariables.get(j);
                    int jIdx = varOrder.indexOf(vj.name);
                    if (jIdx < 0 || vj.fixed) continue;
                    int jOff = varOffsets.get(jIdx);
                    SimpleMatrix Jj = f.jacobian(j);
                    for (int i = 0; i < f.connectedVariables.size(); i++) {
                        Variable vi = f.connectedVariables.get(i);
                        int iIdx = varOrder.indexOf(vi.name);
                        if (iIdx < 0 || vi.fixed) continue;
                        int iOff = varOffsets.get(iIdx);
                        SimpleMatrix Ji = f.jacobian(i);
                        SimpleMatrix block = Ji.transpose().mult(Rinv).mult(Jj);
                        for (int rr = 0; rr < vi.dimension; rr++) {
                            for (int cc = 0; cc < vj.dimension; cc++) {
                                H.set(iOff + rr, jOff + cc, H.get(iOff + rr, jOff + cc) + block.get(rr, cc));
                            }
                        }
                    }
                }
            }
            result.covariance = H.invert();
        }

        return result;
    }

    public static class OptimizationResult {
        public boolean converged;
        public int iterations;
        public double initialError;
        public double finalError;
        public SimpleMatrix covariance;

        @Override
        public String toString() {
            return String.format("OptResult[conv=%b, iter=%d, err=%.6f->%.6f]",
                    converged, iterations, initialError, finalError);
        }
    }
}