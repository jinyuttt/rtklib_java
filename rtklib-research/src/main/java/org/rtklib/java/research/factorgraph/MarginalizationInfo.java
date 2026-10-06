package org.rtklib.java.research.factorgraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ejml.simple.SimpleMatrix;

/**
 * 边缘化信息（移植自FE-GUT marginalization_info.h）。
 *
 * <p>滑动窗口边缘化的Schur消元：
 * <ol>
 *   <li>收集窗口内残差块</li>
 *   <li>构建 Hessian: H = JᵀWJ</li>
 *   <li>分块: 待边缘化参数(m) / 保留参数(r)</li>
 *   <li>Schur: H_new = H_rr - H_rm·H_mm⁻¹·H_mr</li>
 *   <li>特征分解: H_new = V·S·Vᵀ, J0 = S^{1/2}·Vᵀ</li>
 * </ol>
 */
public class MarginalizationInfo {

    private static final double EPS = 1e-8;

    private final Map<Integer, Integer> paramBlockSize = new HashMap<>();
    private final Map<Integer, Integer> paramBlockIndex = new HashMap<>();
    private final Map<Integer, double[]> paramBlockData = new HashMap<>();

    private List<double[]> remainedBlockData;
    private List<Integer> remainedBlockSize;
    private List<Integer> remainedBlockIndex;

    private int marginalizedSize;
    private int remainedSize;
    private int localSize;

    private List<ResidualBlockInfo> factors = new ArrayList<>();

    private SimpleMatrix H0;
    private SimpleMatrix b0;
    private SimpleMatrix Hp;
    private SimpleMatrix bp;

    private SimpleMatrix linearizedJacobians;
    private SimpleMatrix linearizedResiduals;

    private boolean valid = true;

    public MarginalizationInfo() {}

    public boolean isValid() {
        return valid;
    }

    public int marginalizedSize() {
        return marginalizedSize;
    }

    public int remainedSize() {
        return remainedSize;
    }

    public static int localSize(int size) {
        return size;
    }

    public static int globalSize(int size) {
        return size;
    }

    public void addResidualBlockInfo(ResidualBlockInfo blockInfo) {
        factors.add(blockInfo);

        List<double[]> paramBlocks = blockInfo.parameterBlocks();
        List<Integer> blockSizes = blockInfo.parameterBlockSizes();

        for (int k = 0; k < paramBlocks.size(); k++) {
            int key = System.identityHashCode(paramBlocks.get(k));
            paramBlockSize.put(key, blockSizes.get(k));
        }

        for (int index : blockInfo.marginalizationParametersIndex()) {
            int key = System.identityHashCode(paramBlocks.get(index));
            paramBlockIndex.put(key, 0);
        }
    }

    public boolean marginalization() {
        if (!updateParameterBlocksIndex()) {
            valid = false;
            releaseMemory();
            return false;
        }

        preMarginalization();
        constructEquation();
        schurElimination();
        linearization();
        releaseMemory();

        return true;
    }

    public List<double[]> getParameterBlocks(Map<Integer, double[]> address) {
        List<double[]> remainedBlockAddr = new ArrayList<>();
        remainedBlockData = new ArrayList<>();
        remainedBlockIndex = new ArrayList<>();
        remainedBlockSize = new ArrayList<>();

        for (Map.Entry<Integer, Integer> block : paramBlockIndex.entrySet()) {
            if (block.getValue() >= marginalizedSize) {
                int key = block.getKey();
                remainedBlockData.add(paramBlockData.get(key));
                remainedBlockSize.add(paramBlockSize.get(key));
                remainedBlockIndex.add(paramBlockIndex.get(key));
                remainedBlockAddr.add(address.get(key));
            }
        }

        return remainedBlockAddr;
    }

    public SimpleMatrix linearizedJacobians() {
        return linearizedJacobians;
    }

    public SimpleMatrix linearizedResiduals() {
        return linearizedResiduals;
    }

    public List<Integer> remainedBlockSize() {
        return remainedBlockSize;
    }

    public List<Integer> remainedBlockIndex() {
        return remainedBlockIndex;
    }

    public List<double[]> remainedBlockData() {
        return remainedBlockData;
    }

    // ---- private methods ----

    private void linearization() {
        SimpleMatrix H = Hp;

        EigenDecomposition eigen = eigenDecomp(H);

        SimpleMatrix S = eigen.S;
        for (int i = 0; i < S.numRows(); i++) {
            if (S.get(i, i) < EPS) S.set(i, i, 0);
        }

        SimpleMatrix SInv = new SimpleMatrix(S.numRows(), S.numCols());
        SimpleMatrix SInvSqrt = new SimpleMatrix(S.numRows(), S.numCols());

        for (int i = 0; i < S.numRows(); i++) {
            double sv = S.get(i, i);
            if (sv > EPS) {
                SInv.set(i, i, 1.0 / sv);
                SInvSqrt.set(i, i, 1.0 / Math.sqrt(sv));
            }
        }

        SimpleMatrix V = eigen.V;
        SimpleMatrix Vt = V.transpose();

        linearizedJacobians = diagonalMultiply(S, true).mult(Vt);

        SimpleMatrix negBp = bp.scale(-1);
        linearizedResiduals = SInvSqrt.mult(Vt).mult(negBp);
    }

    private SimpleMatrix diagonalMultiply(SimpleMatrix diag, boolean sqrt) {
        SimpleMatrix result = new SimpleMatrix(diag.numRows(), diag.numCols());
        for (int i = 0; i < diag.numRows(); i++) {
            double v = diag.get(i, i);
            result.set(i, i, sqrt ? Math.sqrt(v) : v);
        }
        return result;
    }

    private void schurElimination() {
        SimpleMatrix Hmm = H0.extractMatrix(0, marginalizedSize, 0, marginalizedSize);
        Hmm = Hmm.plus(Hmm.transpose()).scale(0.5);

        SimpleMatrix Hmr = H0.extractMatrix(0, marginalizedSize, marginalizedSize, localSize);
        SimpleMatrix Hrm = H0.extractMatrix(marginalizedSize, localSize, 0, marginalizedSize);
        SimpleMatrix Hrr = H0.extractMatrix(marginalizedSize, localSize, marginalizedSize, localSize);
        SimpleMatrix bmm = extractColumn(b0, 0, marginalizedSize);
        SimpleMatrix brr = extractColumn(b0, marginalizedSize, localSize);

        EigenDecomposition eigen = eigenDecomp(Hmm);
        SimpleMatrix HmmInv = eigen.V.mult(eigen.SInv).mult(eigen.V.transpose());

        Hp = Hrr.minus(Hrm.mult(HmmInv).mult(Hmr));
        bp = brr.minus(Hrm.mult(HmmInv).mult(bmm));
    }

    private static SimpleMatrix extractColumn(SimpleMatrix m, int start, int end) {
        SimpleMatrix v = new SimpleMatrix(end - start, 1);
        for (int i = start; i < end; i++) v.set(i - start, 0, m.get(i, 0));
        return v;
    }

    private void constructEquation() {
        H0 = new SimpleMatrix(localSize, localSize);
        b0 = new SimpleMatrix(localSize, 1);

        for (ResidualBlockInfo factor : factors) {
            List<double[]> pBlocks = factor.parameterBlocks();
            List<Integer> blockSizes = factor.parameterBlockSizes();

            for (int i = 0; i < pBlocks.size(); i++) {
                int row0 = paramBlockIndex.get(System.identityHashCode(pBlocks.get(i)));
                int rows = blockSizes.get(i);

                SimpleMatrix Ji = factor.jacobians().get(i);

                for (int j = i; j < pBlocks.size(); j++) {
                    int col0 = paramBlockIndex.get(System.identityHashCode(pBlocks.get(j)));
                    int cols = blockSizes.get(j);

                    SimpleMatrix Jj = factor.jacobians().get(j);
                    SimpleMatrix block = Ji.transpose().mult(Jj);

                    insertIntoMatrix(H0, row0, col0, block);

                    if (i != j) {
                        insertIntoMatrix(H0, col0, row0, block.transpose());
                    }
                }

                SimpleMatrix negJTe = Ji.transpose().mult(factor.residuals()).scale(-1);
                for (int k = 0; k < rows; k++) {
                    b0.set(row0 + k, 0, b0.get(row0 + k, 0) + negJTe.get(k, 0));
                }
            }
        }
    }

    private void insertIntoMatrix(SimpleMatrix dest, int row, int col, SimpleMatrix block) {
        for (int i = 0; i < block.numRows(); i++) {
            for (int j = 0; j < block.numCols(); j++) {
                dest.set(row + i, col + j, dest.get(row + i, col + j) + block.get(i, j));
            }
        }
    }

    private boolean updateParameterBlocksIndex() {
        int index = 0;

        for (Map.Entry<Integer, Integer> block : paramBlockIndex.entrySet()) {
            block.setValue(index);
            index += paramBlockSize.get(block.getKey());
        }
        marginalizedSize = index;

        for (Map.Entry<Integer, Integer> block : paramBlockSize.entrySet()) {
            if (!paramBlockIndex.containsKey(block.getKey())) {
                paramBlockIndex.put(block.getKey(), index);
                index += block.getValue();
            }
        }
        remainedSize = index - marginalizedSize;
        localSize = index;

        return marginalizedSize > 0;
    }

    private void preMarginalization() {
        for (ResidualBlockInfo factor : factors) {
            factor.evaluate();

            List<double[]> pBlocks = factor.parameterBlocks();
            List<Integer> blockSizes = factor.parameterBlockSizes();

            for (int k = 0; k < pBlocks.size(); k++) {
                int key = System.identityHashCode(pBlocks.get(k));
                int size = blockSizes.get(k);

                if (!paramBlockData.containsKey(key)) {
                    double[] data = new double[size];
                    System.arraycopy(pBlocks.get(k), 0, data, 0, size);
                    paramBlockData.put(key, data);
                }
            }
        }
    }

    private void releaseMemory() {
        factors.clear();
    }

    private static EigenDecomposition eigenDecomp(SimpleMatrix M) {
        int n = M.numRows();

        org.ejml.data.DMatrixRMaj mat = new org.ejml.data.DMatrixRMaj(n, n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                mat.set(i, j, M.get(i, j));
            }
        }

        org.ejml.dense.row.decomposition.eig.SymmetricQRAlgorithmDecomposition_DDRM decomp =
            new org.ejml.dense.row.decomposition.eig.SymmetricQRAlgorithmDecomposition_DDRM(true);
        boolean success = decomp.decompose(mat);

        if (!success) {
            SimpleMatrix I = SimpleMatrix.identity(n);
            return new EigenDecomposition(I, I, I);
        }

        int numEigen = decomp.getNumberOfEigenvalues();
        double[] eigenvalues = new double[numEigen];
        SimpleMatrix V = new SimpleMatrix(n, numEigen);

        for (int i = 0; i < numEigen; i++) {
            eigenvalues[i] = decomp.getEigenvalue(i).getReal();
            org.ejml.data.DMatrixRMaj vec = decomp.getEigenVector(i);
            if (vec != null) {
                for (int j = 0; j < n; j++) {
                    V.set(j, i, vec.get(j, 0));
                }
            }
        }

        SimpleMatrix S = new SimpleMatrix(numEigen, numEigen);
        SimpleMatrix SInv = new SimpleMatrix(numEigen, numEigen);
        for (int i = 0; i < numEigen; i++) {
            double val = eigenvalues[i];
            if (val > EPS) {
                S.set(i, i, val);
                SInv.set(i, i, 1.0 / val);
            }
        }

        return new EigenDecomposition(V, S, SInv);
    }

    private static class EigenDecomposition {
        final SimpleMatrix V;
        final SimpleMatrix S;
        final SimpleMatrix SInv;

        EigenDecomposition(SimpleMatrix V, SimpleMatrix S, SimpleMatrix SInv) {
            this.V = V;
            this.S = S;
            this.SInv = SInv;
        }
    }
}