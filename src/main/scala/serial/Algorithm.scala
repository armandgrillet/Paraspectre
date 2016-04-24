package serial

import breeze.linalg._
import breeze.numerics._
import breeze.plot._
import breeze.stats._
import java.awt.{Color, Paint}
import java.io.File
import scala.io.Source

object Algorithm {
    // Parameters.
    val k = 7 // K'th neighbor used in local scaling.
    val minClusters = 2 // Minimal number of clusters in the dataset.
    val maxClusters = 6 // Maximal number of clusters in the dataset.

    def main(args: Array[String]) = {
        // Choose the dataset to cluster.
        val pathToMatrix = getClass.getResource("/5.csv").getPath()
        val matrixFile = new File(pathToMatrix)

        // Create a DenseMatrix from the CSV.
        val originalMatrix = breeze.linalg.csvread(matrixFile)

        // Centralizing and scale the data.
        val meanCols = mean(originalMatrix(::, *)).t.toDenseMatrix
        var matrix = (originalMatrix - vertStack(meanCols, originalMatrix.rows))
        matrix /= max(abs(matrix))
        // val matrix = originalMatrix

        // Compute local scale (step 1).
        val distances = euclideanDistance(matrix)
        val locScale = localScale(distances, k)

        // Build locally scaled affinity matrix (step 2).
        var locallyScaledA = locallyScaledAffinityMatrix(distances, locScale)

        // Build the normalized affinity matrix (step 3)
        val diagonalMatrix = diag(pow(sum(locallyScaledA(*, ::)), -0.5)) // Sum of each row, then power -0.5, then matrix.
        val normalizedA = diagonalMatrix * locallyScaledA * diagonalMatrix

        // Copy of the beginning of SpectralClustering.cpp
        // val sumRows = sum(normalizedA(::, *))
        // val diag = DenseMatrix.zeros[Double](normalizedA.rows, normalizedA.cols)
        // for (col <- 0 to normalizedA.cols) {
        //     diag(col, col) = 1 / sqrt(sumRows(col))
        // }
        // val laplacian = diag * normalizedA * diag

        // Compute eigenvectors
        val eigenstuff = eig(normalizedA)
        var eigenvalues = eigenstuff.eigenvalues // DenseVector
        var eigenvectors = eigenstuff.eigenvectors // DenseMatrix

        var col, row = 0
        for (col <- 0 until eigenvalues.length) { // until = to - 1
            val k = argmax(eigenvalues(col until normalizedA.cols))
            if (k > 0) {
                val tempValue = eigenvalues(col)
                eigenvalues(col) = eigenvalues(k + col)
                eigenvalues(k + col) = tempValue

                val tempVector = eigenvectors(::, col)
                for (row <- 0 until eigenvectors.rows) {
                    eigenvectors(row, col) = eigenvectors(row, k + col)
                    eigenvectors(row, k + col) = tempVector(row)
                }
            }
        }

        // printVector(eigenvalues(0 to 10))
        eigenvectors = eigenvectors(::, 0 until maxClusters)

        // In cluster_rotate.m originally
        var currentEigenvectors = eigenvectors(::, 0 until minClusters)
        var (cost, clusters, rotatedEigenvectors) = paraspectre(currentEigenvectors)

        print(minClusters)
        print(" clusters:\t")
        println(cost)

        var group = 0
        for (group <- minClusters until maxClusters) {
            val eigenvectorToAdd = eigenvectors(::, group).toDenseMatrix.t
            currentEigenvectors = DenseMatrix.horzcat(rotatedEigenvectors, eigenvectorToAdd)
            val (tempCost, tempClusters, tempRotatedEigenvectors) = paraspectre(currentEigenvectors)
            rotatedEigenvectors = tempRotatedEigenvectors
            print(group + 1)
            print(" clusters:\t")
            println(tempCost)
            if (tempCost <= (cost + 0.001)) {
                cost = tempCost
                clusters = tempClusters
            }
        }
        // In evrot.cpp originally

        // val f = Figure()
        // val id2Color: Int => Paint = id => id match {
        //     case 0 => Color.YELLOW
        //     case 1 => Color.RED
        //     case 2 => Color.GREEN
        //     case 3 => Color.BLUE
        //     case 4 => Color.GRAY
        //     case _ => Color.BLACK
        //   }
        //
        // f.subplot(0) +=  scatter(originalMatrix(::, 0), originalMatrix(::, 1), {(_:Int) => 1.0}, {(_:Int) => Color.BLACK})
        // f.subplot(0).xlabel = "X-coordinate"
        // f.subplot(0).ylabel = "Y-coordinate"
    }
}
