import breeze.linalg._
import breeze.numerics._
import breeze.stats._
import breeze.plot._
import java.awt.{Color, Paint}
import org.jfree.chart.axis.{NumberTickUnit, TickUnits}

package object serial {
    def vertStack(matrix: DenseMatrix[Double], iterations: Int): DenseMatrix[Double] = {
        var stack = matrix
        var i = 0
        while (i < iterations - 1) {
            stack = DenseMatrix.vertcat(stack, matrix)
            i += 1
        }
        return stack
    }

    def euclideanDistance(matrix: DenseMatrix[Double]): DenseMatrix[Double] = {
        var distanceMatrix = DenseMatrix.zeros[Double](matrix.rows, matrix.rows) // Distance matrix, size rows x rows.
        var distanceVector = DenseVector(0.0).t // The distance vector containing the distance between two vectors.

        (0 until matrix.rows).map{ mainRow =>
            (mainRow + 1 until matrix.rows).map{ secondRow =>
                distanceVector = matrix(mainRow, ::) - matrix(secondRow,::) // Xi - Xj | Yi - Yj
                distanceVector *= distanceVector // (Xi - Xj)² | (Yi - Yj)²
                distanceMatrix(mainRow, secondRow) = sqrt(sum(distanceVector)) // √(Xi - Xj)² + (Yi - Yj)² + ...
                distanceMatrix(secondRow, mainRow) = distanceMatrix(mainRow, secondRow)
            }
        }

        return distanceMatrix
    }

    def localScale(distanceMatrix: DenseMatrix[Double], k: Int): DenseVector[Double] = {
        if (k > distanceMatrix.cols - 1) {
            return max(distanceMatrix(*, ::)) // Maximum distance.
        } else {
            var localScale = DenseVector.zeros[Double](distanceMatrix.cols)
            var sortedVector = IndexedSeq(0.0)

            (0 until distanceMatrix.cols).map{col =>
                sortedVector = distanceMatrix(::, col).toArray.sorted
                localScale(col) = sortedVector(k) // Kth nearest distance., the 0th neighbor is always 0 and sortedVector(1) is the first neighbor
            }

            return localScale
        }
    }

    def locallyScaledAffinityMatrix(distanceMatrix: DenseMatrix[Double], localScale: DenseVector[Double]): DenseMatrix[Double] = {
        var affinityMatrix = DenseMatrix.zeros[Double](distanceMatrix.rows, distanceMatrix.cols) // Distance matrix, size rows x cols.

        (0 until distanceMatrix.rows).map{ row =>
            (row + 1 until distanceMatrix.cols).map{ col =>
                affinityMatrix(row, col) = -scala.math.pow(distanceMatrix(row, col), 2) // -d(si, sj)²
                affinityMatrix(row, col) /= (localScale(row) * localScale(col)) // -d(si, sj)² / lambi * lambj
                affinityMatrix(row, col) = scala.math.exp(affinityMatrix(row, col)) // exp(-d(si, sj)² / lambi * lambj)
                affinityMatrix(col, row) = affinityMatrix(row, col)
            }
        }

        return affinityMatrix
    }

    def printVector(vector: DenseVector[Double]) {
        val f = Figure()
        val p = f.subplot(0)
        p.title = "First 10 eigenvalues of L"
        p.xlim(0, vector.length - 1)
        p.ylim(0.9, 1.01)
        p.yaxis.setTickUnit(new NumberTickUnit(0.01));

        val xVector = linspace(0, vector.length - 1, vector.length)

        p += scatter(xVector, vector, {(_:Int) => 0.3}, {(_:Int) => Color.RED}) // Display the observations.
    }

    def logicalNot(matrix: DenseMatrix[Double]): DenseMatrix[Double] = {
        return DenseMatrix.tabulate(matrix.rows, matrix.cols){
            case (row, col) =>
            if (matrix(row, col) == 0.0) {
                1.0
            } else {
                0.0
            }
        }
    }

    def paraspectre(eigenvectors: DenseMatrix[Double]): (Double, DenseVector[Int], DenseMatrix[Double]) = {
        // Get the number of angles
        val angles = (eigenvectors.cols * (eigenvectors.cols-1) / 2).toInt
        val maxIterations = 200
        var angle = 0
        val theta, newTheta = DenseVector.zeros[Double](angles)

        // Definitions
        val stepSize = 1.0
        var penultimateCost, lastCost, currentCost, newCost = cost(eigenvectors)
        var rotatedEigenvectors = DenseMatrix.zeros[Double](0, 0)

        var iteration = 0
        while (iteration < maxIterations) {
            iteration += 1
            for (angle <- 0 until angles) {
                val gradient = stochasticGradient(eigenvectors, theta, angles, angle)
                newTheta(angle) = theta(angle) - stepSize * gradient
                rotatedEigenvectors = givensRotate(eigenvectors, newTheta, angles)
                newCost = cost(rotatedEigenvectors)
                if (newCost < currentCost) {
                    theta(angle) = newTheta(angle)
                    currentCost = newCost
                } else {
                    newTheta(angle) = theta(angle)
                }
            }
            if (iteration > 2 && abs(currentCost - penultimateCost) < 1e-3) { // Stopping criteria
                iteration = maxIterations
            } else {
                penultimateCost = lastCost
                lastCost = currentCost
            }
        }

        rotatedEigenvectors = givensRotate(eigenvectors, newTheta, angles)
        val clusts = clusters(rotatedEigenvectors)
        return (currentCost, clusts, rotatedEigenvectors)
    }

    // Sum of each row divided by the maximum of each row.
    def cost(rotatedMatrix: DenseMatrix[Double]): Double = {
        val squaredMatrix = rotatedMatrix :* rotatedMatrix
        return sum(sum(squaredMatrix(*, ::)) / max(squaredMatrix(*, ::)))
    }

    // Position i,j of an index in a col*col matrix.
    def upperIndex(cols: Int, index: Int): (Int, Int) = {
        var idx, i, j = 0
        while (i < cols - 1) {
            j = i + 1
            while (j < cols) {
                if (idx == index) {
                    return (i, j)
                }
                j += 1
                idx += 1
            }
            i += 1
        }
        return (0, 0)
    }

    def stochasticGradient(eigenvectors: DenseMatrix[Double], theta: DenseVector[Double], angles: Int, angle: Int): Double = {
        // In C++ it is a 1D array but arr(rows * row + col) = mat(row, col)
        var gradients = DenseMatrix.zeros[Double](eigenvectors.cols, eigenvectors.cols)
        val (i, j) = upperIndex(eigenvectors.cols, angle)
        gradients(i, i) = -sin(theta(angle))
        gradients(j, i) = cos(theta(angle))
        gradients(i, j) = -cos(theta(angle))
        gradients(j, j) = -sin(theta(angle))

        val u1 = uAB(theta, 0, angle - 1, eigenvectors.cols)
        val u2 = uAB(theta, angle + 1, angles - 1, eigenvectors.cols)

        val a = eigenvectors * u1 * gradients * u2

        // Rotate vectors according to angles.
        val y = givensRotate(eigenvectors, theta, angles)

        // Find the maximum of each row
        val squaredY = y :* y // :* = Hadamard product
        val maxEachRow = sqrt(max(squaredY(*, ::))) // Sqrt because in the original code max_values[i] = p_Y[ind];
        val argMaxEachRow = argmax(squaredY(*, ::)) // No sqrt because it is a position

        // Compute gradient
        var tmp1, tmp2, quality = 0.0
        var col = 0
        while (col < eigenvectors.cols) {
            var row = 0
            while (row < eigenvectors.rows) {
                tmp1 = a(row, col) * y(row, col) / scala.math.pow(maxEachRow(row), 2)
                tmp2 = a.toDenseVector(eigenvectors.rows * argMaxEachRow(row) + row) * squaredY(row, col) / scala.math.pow(maxEachRow(row), 3)
                quality += tmp1 - tmp2
                row += 1
            }
            col += 1
        }

        return 2 * quality / eigenvectors.rows / eigenvectors.cols
    }

    def uAB(theta: DenseVector[Double], a: Int, b: Int, cols: Int): DenseMatrix[Double] = {
        // Set uab to be an identity matrix
        var uab = DenseMatrix.eye[Double](cols)

        if (b < a) {
            return uab
        }

        var k = a
        var tt, uIndex = 0.0
        for (k <- a to b) {
            tt = theta(k)
            val (i, j) = upperIndex(cols, k)
            var col = 0
            for (col <- 0 until cols) {
                uIndex = uab(i, col) * cos(tt) - uab(j, col) * sin(tt)
                uab(j, col) = uab(i, col) * sin(tt) * uab(j, col) * cos(tt)
                uab(i, col) = uIndex
            }
        }

        return uab
    }

    def givensRotate(eigenvectors: DenseMatrix[Double], theta: DenseVector[Double], angles: Int): DenseMatrix[Double] = {
        val g = uAB(theta, 0, angles - 1, eigenvectors.cols)
        return eigenvectors * g
    }

    def clusters(rotatedEigenvectors: DenseMatrix[Double]): DenseVector[Int] = {
        val squaredVectors = rotatedEigenvectors :* rotatedEigenvectors
        return argmax(squaredVectors(*, ::))
    }
}
