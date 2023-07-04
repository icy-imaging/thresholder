/*
 * Copyright (c) 2010-2023. Institut Pasteur.
 *
 * This file is part of Icy.
 * Icy is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Icy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Icy. If not, see <https://www.gnu.org/licenses/>.
 */

package plugins.adufour.thresholder;

import icy.sequence.Sequence;
import icy.type.collection.array.Array1DUtil;

import java.util.Arrays;

public class KMeans {
    public static final int DEFAULT_KMEANS_BINS = 255;

    /**
     * Calculates the optimal thresholds on the input data for the given number of classes and
     * default bins size
     *
     * @param input     the input sequence
     * @param nbClasses the number of classes to extract
     * @return an array of thresholds of size [input.getSizeC()][nbClasses-1]
     */
    public static double[][] computeKMeansThresholds(final Sequence input, final int nbClasses) {
        return computeKMeansThresholds(input, nbClasses, DEFAULT_KMEANS_BINS);
    }

    /**
     * Calculates the optimal thresholds on the input data for the given number of classes and bins
     * size
     *
     * @param input        the input sequence
     * @param nbClasses    the number of classes to extract
     * @param binPrecision the size of the histogram bins (higher is slower but more precise)
     * @return an array of thresholds of size [input.getSizeC()][nbClasses-1]
     */
    public static double[][] computeKMeansThresholds(final Sequence input, final int nbClasses, final int binPrecision) {
        final double[][] thrs = new double[input.getSizeC()][];

        for (int c = 0; c < input.getSizeC(); c++)
            thrs[c] = computeKMeansThresholds(input, c, nbClasses, binPrecision);

        return thrs;
    }

    /**
     * Calculates the optimal thresholds on the specified channel of the input data for the given
     * number of classes and bins size
     *
     * @param input        the input sequence
     * @param c            the channel on which to compute the threshold(s)
     * @param nbClasses    the number of classes to extract
     * @param binPrecision the size of the histogram bins (higher is slower but more precise)
     * @return an array of thresholds for the given channel, of size [nbClasses-1]
     */
    public static double[] computeKMeansThresholds(final Sequence input, final int c, final int nbClasses, final int binPrecision) {
        final double[] thresholds = new double[nbClasses - 1];

        input.loadAllData();
        input.updateChannelsBounds(true);
        final double[] minmax = input.getChannelBounds(c);
        final double min = minmax[0];
        final double max = minmax[1];
        final double fact = (binPrecision - 1) / (max - min);
        final double[] histo = new double[binPrecision];

        // histogram computation
        {
            final double[] sliceXY = new double[input.getSizeX() * input.getSizeY()];

            for (int t = 0; t < input.getSizeT(); t++)
                for (int z = 0; z < input.getSizeZ(); z++) {
                    Array1DUtil.arrayToDoubleArray(input.getDataXY(t, z, c), sliceXY, input.isSignedDataType());
                    for (final double d : sliceXY)
                        histo[(int) ((d - min) * fact)]++;
                }
        }

        final int[] centers = kMeans_Histogram1D(histo, nbClasses);

        // Compute thresholds between class centers

        java.util.Arrays.sort(centers);
        for (int k = 1; k < nbClasses; k++) {
            thresholds[k - 1] = min + (centers[k - 1] + (centers[k] - centers[k - 1]) / 2.0) / fact;
        }

        return thresholds;
    }

    /**
     * Calculates the optimal thresholds on the specified channel of the input data for the given
     * number of classes and bins size
     *
     * @param input        the input sequence
     * @param c            the channel on which to compute the threshold(s)
     * @param t            The timepoint on which to compute the threshold(s).
     * @param nbClasses    the number of classes to extract
     * @param binPrecision the size of the histogram bins (higher is slower but more precise)
     * @return an array of thresholds for the given channel, of size [nbClasses-1]
     */
    public static double[] computeKMeansThresholds(final Sequence input, final int c, final int t, final int nbClasses, final int binPrecision) {
        final double[] thresholds = new double[nbClasses - 1];

        // compute min/max on the current stack
        input.getImage(t, 0).loadData();
        double min = input.getImage(t, 0).getChannelMin(c);
        double max = input.getImage(t, 0).getChannelMax(c);

        for (int z = 1; z < input.getSizeZ(); z++) {
            final double[] sliceBounds = input.getImage(t, z).getChannelBounds(c);
            if (sliceBounds[0] < min)
                min = sliceBounds[0];
            if (sliceBounds[1] > max)
                max = sliceBounds[1];
        }

        final double fact = (binPrecision - 1) / (max - min);
        final double[] histo = new double[binPrecision];

        // histogram computation
        {
            final double[] sliceXY = new double[input.getSizeX() * input.getSizeY()];

            for (int z = 0; z < input.getSizeZ(); z++) {
                Array1DUtil.arrayToDoubleArray(input.getDataXY(t, z, c), sliceXY, input.isSignedDataType());
                for (final double d : sliceXY)
                    histo[(int) ((d - min) * fact)]++;
            }
        }

        final int[] centers = kMeans_Histogram1D(histo, nbClasses);

        // Compute thresholds between class centers

        java.util.Arrays.sort(centers);
        for (int k = 1; k < nbClasses; k++) {
            thresholds[k - 1] = min + (centers[k - 1] + (centers[k] - centers[k - 1]) / 2.0) / fact;
        }

        return thresholds;
    }

    public static double[][] computeKMeansThresholds(final Sequence inSeq, final int c, final boolean timeDependent, final short nbClasses,
                                                     final int nbBins) {
        final double[][] thrs = new double[inSeq.getSizeT()][];

        if (timeDependent) {
            for (int t = 0; t < thrs.length; t++) {
                thrs[t] = computeKMeansThresholds(inSeq, c, t, nbClasses, nbBins);
            }
        }
        else {
            final double[] thr = computeKMeansThresholds(inSeq, c, nbClasses, nbBins);

            Arrays.fill(thrs, thr);
        }

        return thrs;
    }

    /**
     * KMeans classification algorithm, optimized for 1D histogram data. The algorithm is
     * initialized by spacing the class centers equally
     *
     * @param histogram the histogram to classify
     * @param nbClasses the number of classes to extract
     * @return the optimal class centers after convergence
     */
    public static int[] kMeans_Histogram1D(final double[] histogram, final int nbClasses) {
        final int[] centers = new int[nbClasses];
        final double[] sums = new double[nbClasses];
        final double[] nbElements = new double[nbClasses];

        // basic class initialization : regularly divide the space

        for (int i = 0; i < nbClasses; i++) {
            centers[i] = (int) ((histogram.length - 1f) * (i + 1f) / (nbClasses + 1f));
        }

        // main loop

        boolean convergence = false;

        while (!convergence) {
            // assume the convergence is reached
            // (invalidate this assumption later if class means move)
            convergence = true;

            java.util.Arrays.fill(nbElements, 0);
            java.util.Arrays.fill(sums, 0);

            for (int i = 0; i < histogram.length; i++) {
                int closestClass = 0;
                double minDistance = Double.MAX_VALUE;

                // compute the shortest distance from the current bin
                // to a class center and assign the bin to that class
                for (int k = 0; k < nbClasses; k++) {
                    final double distance = Math.abs(i - centers[k]);

                    if (distance < minDistance) {
                        minDistance = distance;
                        closestClass = k;
                    }
                }

                // to update each class center to the mean,
                // accumulate both number and sum per class
                final double nbElemInCurrentBin = histogram[i];
                sums[closestClass] += i * nbElemInCurrentBin;
                nbElements[closestClass] += nbElemInCurrentBin;
            }

            // once all bins have been assigned to a class,
            // the class centers can be moved toward the new means

            for (int k = 0; k < nbClasses; k++) {
                final int oldCenter = centers[k];
                final int newCenter = (int) (sums[k] / nbElements[k]);

                convergence &= (oldCenter == newCenter);

                centers[k] = newCenter;
            }
        }

        return centers;
    }
}
