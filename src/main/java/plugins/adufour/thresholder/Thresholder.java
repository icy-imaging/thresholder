/*
 * Copyright (c) 2010-2024. Institut Pasteur.
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

import org.bioimageanalysis.extension.kernel.roi.roi2d.ROI2DArea;
import org.bioimageanalysis.extension.kernel.roi.roi3d.ROI3DArea;
import org.bioimageanalysis.icy.common.collection.array.Array1DUtil;
import org.bioimageanalysis.icy.common.type.DataType;
import org.bioimageanalysis.icy.extension.plugin.annotation_.IcyPluginIcon;
import org.bioimageanalysis.icy.extension.plugin.annotation_.IcyPluginName;
import org.bioimageanalysis.icy.gui.frame.progress.FailedAnnounceFrame;
import org.bioimageanalysis.icy.model.image.IcyBufferedImage;
import org.bioimageanalysis.icy.model.roi.ROI;
import org.bioimageanalysis.icy.model.roi.mask.BooleanMask2D;
import org.bioimageanalysis.icy.model.sequence.Sequence;
import plugins.adufour.blocks.lang.Block;
import plugins.adufour.blocks.util.VarList;
import plugins.adufour.ezplug.*;
import plugins.adufour.roi.LabelExtractor;
import plugins.adufour.roi.LabelExtractor.ExtractionType;
import plugins.adufour.vars.lang.VarROIArray;
import plugins.adufour.vars.lang.VarSequence;
import plugins.adufour.vars.util.VarException;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@IcyPluginName("Thresholder")
@IcyPluginIcon(path = "/thresholder.png")
public class Thresholder extends EzPlug {
    private enum ThresholdMethod {
        MANUAL, K_MEANS
    }

    private enum ThresholdOutput {
        SEQUENCE("Labeled sequence"), ROI("Single ROI"), MULTI_ROI("Multiple ROI");

        final String description;

        ThresholdOutput(final String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    private final EzVarSequence in = new EzVarSequence("Input");
    private final EzVarChannel channel = new EzVarChannel("channel", in.getVariable(), false);
    private final EzVarEnum<ThresholdMethod> method = new EzVarEnum<>("Method", ThresholdMethod.values(), ThresholdMethod.MANUAL);
    private final EzVarInteger nbClasses = new EzVarInteger("K-means classes", 2, KMeans.DEFAULT_KMEANS_BINS, 1);
    private final EzVarDoubleArrayNative thresholds = new EzVarDoubleArrayNative("Manual thresholds", new double[][]{new double[]{100, 200}}, true);
    private final EzVarBoolean pct = new EzVarBoolean("Treat as percentiles", false);
    private final EzVarBoolean timeDependent = new EzVarBoolean("Process frames independently", false);
    private final EzVarEnum<ThresholdOutput> outputType = new EzVarEnum<>("Output as", ThresholdOutput.values(), ThresholdOutput.SEQUENCE);

    private final EzVarBoolean filterBySize = new EzVarBoolean("Filter by size", false);
    private final EzVarInteger minSize = new EzVarInteger("Min size (px)", 100, 1, 200000000, 1);
    private final EzVarInteger maxSize = new EzVarInteger("Max size (px)", 10000, 1, 200000000, 1);
    private final EzVarBoolean inPlace = new EzVarBoolean("Overwrite input", false);

    private final VarSequence outLabels = new VarSequence("Binary output", null);
    private final VarROIArray outROI = new VarROIArray("ROI");

    private boolean blockMode = false;

    @Override
    public void initialize() {
        super.addEzComponent(in);
        super.addEzComponent(channel);

        super.addEzComponent(method);

        method.addVisibilityTriggerTo(nbClasses, ThresholdMethod.K_MEANS);
        super.addEzComponent(nbClasses);

        method.addVisibilityTriggerTo(thresholds, ThresholdMethod.MANUAL);
        super.addEzComponent(thresholds);

        method.addVisibilityTriggerTo(pct, ThresholdMethod.MANUAL);
        super.addEzComponent(pct);

        method.addVisibilityTriggerTo(timeDependent, ThresholdMethod.K_MEANS);
        super.addEzComponent(timeDependent);

        super.addEzComponent(outputType);

        super.addEzComponent(filterBySize);
        outputType.addVisibilityTriggerTo(filterBySize, ThresholdOutput.MULTI_ROI);

        final EzGroup sizeFilterGroup = new EzGroup("Size filter", minSize, maxSize);
        super.addEzComponent(sizeFilterGroup);
        filterBySize.addVisibilityTriggerTo(sizeFilterGroup, true);

        outputType.addVisibilityTriggerTo(inPlace, ThresholdOutput.SEQUENCE);
        super.addEzComponent(inPlace);
    }

    @Override
    public void execute() {
        final Sequence inSeq = in.getValue(true);

        final int c = channel.getValue();

        if (c >= inSeq.getSizeC()) {
            throw new VarException(channel.getVariable(), "\"" + inSeq.getName() + "\" has no channel \"" + c + "\"");
        }

        final ThresholdMethod algorithm = method.getValue();
        double[][] _thrs = new double[inSeq.getSizeT()][];

        try {
            switch (method.getValue()) {
                case MANUAL: {
                    double[] thrs = thresholds.getValue(true);

                    if (thrs.length == 0)
                        throw new VarException(thresholds.getVariable(), "No threshold(s) indicated");

                    if (pct.getValue()) {
                        for (final double thr : thrs)
                            if (thr < 0.0 || thr > 100.0)
                                throw new VarException(pct.getVariable(), "Percentile(s) must be between 0 and 100");

                        if (!timeDependent.getValue()) {
                            // preserve the original array
                            thrs = Arrays.copyOf(thrs, thrs.length);

                            // compute one global set of threshold percentile for the sequence
                            inSeq.loadAllData();
                            final double min = inSeq.getChannelMin(c);
                            final double max = inSeq.getChannelMax(c);

                            for (int i = 0; i < thrs.length; i++)
                                thrs[i] = min + thrs[i] * (max - min) / 100;
                        }
                    }

                    for (int t = 0; t < inSeq.getSizeT(); t++) {
                        _thrs[t] = Arrays.copyOf(thrs, thrs.length);

                        if (pct.getValue() && timeDependent.getValue()) {
                            // interpret thresholds as intensity percentiles
                            inSeq.getImage(t, 0).loadData();
                            double min = inSeq.getImage(t, 0).getChannelMin(c);
                            double max = inSeq.getImage(t, 0).getChannelMax(c);

                            for (int z = 1; z < inSeq.getSizeZ(); z++) {
                                inSeq.getImage(t, z).loadData();
                                final double[] sliceBounds = inSeq.getImage(t, z).getChannelBounds(c);
                                if (sliceBounds[0] < min)
                                    min = sliceBounds[0];
                                if (sliceBounds[1] > max)
                                    max = sliceBounds[1];
                            }

                            for (int i = 0; i < _thrs[t].length; i++)
                                _thrs[t][i] = min + _thrs[t][i] * (max - min) / 100;
                        }
                    }

                    break;
                }
                case K_MEANS: {
                    _thrs = KMeans.computeKMeansThresholds(inSeq, c, timeDependent.getValue(),
                            nbClasses.getValue().shortValue(), KMeans.DEFAULT_KMEANS_BINS);
                    break;
                }
                default:
                    throw new UnsupportedOperationException(algorithm + " method");
            }

            if (blockMode) {
                if (outLabels.isReferenced()) {
                    final Sequence sOUT = threshold(inSeq, c, _thrs, false);
                    sOUT.setName(inSeq.getName() + "_thresholded");
                    outLabels.setValue(sOUT);
                }

                if (outROI.isReferenced()) {
                    outROI.setValue(threshold(inSeq, c, _thrs));
                }
            }
            else {
                switch (outputType.getValue()) {
                    case SEQUENCE: {
                        final Sequence sOUT = threshold(inSeq, c, _thrs, inPlace.getValue());

                        final StringBuilder newName = new StringBuilder(inSeq.getName() + " thresholded");

                        if (!timeDependent.getValue()) {
                            newName.append(" at value").append((_thrs[0].length == 1 ? " " : "s "));
                            newName.append(_thrs[0][0]);
                            for (int i = 1; i < _thrs[0].length; i++)
                                newName.append(";").append(_thrs[0][i]);
                        }

                        sOUT.setName(newName.toString());

                        if (inPlace.getValue()) {
                            sOUT.dataChanged();
                        }
                        else {
                            addSequence(sOUT);
                        }
                        break;
                    }
                    case ROI: {
                        inSeq.removeAllROI();

                        final ROI[] rois = threshold(inSeq, c, _thrs);

                        for (final ROI roi : rois) {
                            // size check?
                            final double size = roi.getNumberOfPoints();
                            if (!filterBySize.getValue() || (size >= minSize.getValue() && size <= maxSize.getValue())) {
                                inSeq.addROI(roi);
                            }
                        }

                        break;
                    }
                    case MULTI_ROI: {
                        inSeq.removeAllROI();

                        final Sequence sOUT = threshold(inSeq, c, _thrs, inPlace.getValue());
                        final List<ROI> rois = LabelExtractor.extractLabels(sOUT, ExtractionType.ALL_LABELS_VS_BACKGROUND,
                                0.0);
                        for (final ROI roi : rois) {
                            // if (roi instanceof ROI4D) ((ROI4D) roi).setC(c);
                            // else if (roi instanceof ROI3D) ((ROI3D) roi).setC(c);
                            // else if (roi instanceof ROI2D) ((ROI2D) roi).setC(c);

                            // size check?
                            final double size = roi.getNumberOfPoints();
                            if (!filterBySize.getValue() || (size >= minSize.getValue() && size <= maxSize.getValue())) {
                                inSeq.addROI(roi);
                            }
                        }
                    }
                }
            }
        }
        catch (final InterruptedException e) {
            new FailedAnnounceFrame(e.getMessage());
        }
    }

    /**
     * Threshold the given sequence channel with the specified thresholds, and returns the result as
     * a labeled sequence.<br>
     * Note: thresholds are inclusive: values equal to a threshold are considered as
     * "inside the object"
     *
     * @param input      the sequence to threshold
     * @param c          the channel to threshold
     * @param thresholds the list of thresholds (applied on all sequence frames)
     * @param inPlace    true to replace the input by the thresholded data
     * @return the thresholded sequence (if inPlace is true, will return a reference to input)
     */
    public static Sequence threshold(final Sequence input, final int c, final double[] thresholds, final boolean inPlace) {
        final double[][] thresholdsT = new double[input.getSizeT()][];
        Arrays.fill(thresholdsT, thresholds);

        return threshold(input, c, thresholdsT, inPlace);
    }

    /**
     * Threshold the given sequence channel with the specified thresholds, and returns the result as
     * a labeled sequence.<br>
     * Note: thresholds are inclusive: values equal to a threshold are considered as "above"<br>
     *
     * @param input         Input sequence.
     * @param c             Channel index from which the thresholding is performed.
     * @param thresholdsInT a list of thresholds for each time point of the input sequence
     * @param inPlace       If true, the result is stored in the input sequence. A new sequence is returned otherwise.
     * @return The thresholded sequence.
     */
    public static Sequence threshold(final Sequence input, final int c, final double[][] thresholdsInT, final boolean inPlace) {
        if (input == null)
            throw new IllegalArgumentException("Thresholder: no input sequence given");
        if (c >= input.getSizeC())
            throw new IllegalArgumentException("Thresholder: input sequence has no channel #" + c);

        final Sequence output = inPlace ? input : new Sequence();

        final DataType dataType = input.getDataType();

        final int length = input.getSizeX() * input.getSizeY();

        output.beginUpdate();

        int maxClass = 0;

        for (int t = 0; t < input.getSizeT(); t++) {
            final double[] thresholdValues = thresholdsInT[t];

            if (thresholdValues == null || thresholdValues.length == 0)
                throw new IllegalArgumentException("Thresholder: no thresholds given");

            final double thr0 = thresholdValues[0];
            final int maxThresholdIndex = thresholdValues.length - 1;
            if (thresholdValues.length > maxClass)
                maxClass = thresholdValues.length;

            for (int z = 0; z < input.getSizeZ(); z++) {
                final Object _in2D = input.getDataXY(t, z, c);

                final IcyBufferedImage outSlice;

                if (inPlace) {
                    outSlice = input.getImage(t, z);
                }
                else {
                    outSlice = new IcyBufferedImage(input.getSizeX(), input.getSizeY(), 1, dataType);
                    output.setImage(t, z, outSlice);
                }

                final Object _out2D = outSlice.getDataXY(inPlace ? c : 0);

                withTheNextPixel:
                for (int i = 0; i < length; i++) {
                    final double val = _in2D == null ? 0 : Array1DUtil.getValue(_in2D, i, dataType);

                    // background
                    if (val < thr0) {
                        if (inPlace) {
                            Array1DUtil.setValue(_out2D, i, dataType, 0);
                        }
                        continue;
                    }
                    // special 2-class case
                    if (maxThresholdIndex == 0) {
                        Array1DUtil.setValue(_out2D, i, dataType, 1);
                        continue;
                    }

                    // default n-class case

                    // browse thresholds from highest to lowest (above 1 to save one test)
                    // assign the first positive match and break the loop
                    for (int thr = maxThresholdIndex; thr > 0; thr--)
                        if (val >= thresholdValues[thr]) {
                            Array1DUtil.setValue(_out2D, i, dataType, thr + 1);
                            continue withTheNextPixel;
                        }

                    // last possible case: class 1
                    Array1DUtil.setValue(_out2D, i, dataType, 1);
                }

                // to ensure data is correctly saved in image cache (Stephane fix)
                outSlice.setDataXY(inPlace ? c : 0, _out2D);
            }
        }

        output.endUpdate();
        output.getColorModel().setComponentAbsBounds(inPlace ? c : 0, 0, maxClass);
        output.getColorModel().setComponentUserBounds(inPlace ? c : 0, 0, maxClass);
        // output.updateComponentsBounds(true, true);

        return output;
    }

    /**
     * Threshold the given sequence channel with the specified thresholds, and returns the result as
     * a list of regions of interest (ROI).<br>
     * Note: thresholds are inclusive: values equal to a threshold are considered as
     * "inside the object"
     *
     * @param input      the sequence to threshold
     * @param c          the channel to threshold
     * @param thresholds the list of thresholds (applied on all sequence frames)
     * @return the thresholded sequence (if inPlace is true, will return a reference to input)
     */
    public static ROI[] threshold(final Sequence input, final int c, final double[] thresholds) throws InterruptedException {
        final double[][] thresholdsT = new double[input.getSizeT()][];
        Arrays.fill(thresholdsT, thresholds);

        return threshold(input, c, thresholdsT);
    }

    /**
     * Threshold the given sequence channel with the specified thresholds, and returns the result as
     * a labeled sequence.<br>
     * Note: thresholds are inclusive: values equal to a threshold are considered as "above"<br>
     *
     * @param input              Input sequence.
     * @param c                  Target channel.
     * @param thresholdsOverTime a list of thresholds for each time point of the input sequence
     * @return Array of threshold result ROIs, one per label.
     */
    public static ROI[] threshold(final Sequence input, final int c, final double[][] thresholdsOverTime) throws InterruptedException {
        if (input == null)
            throw new IllegalArgumentException("Thresholder: no input sequence given");
        if (c >= input.getSizeC())
            throw new IllegalArgumentException("Thresholder: input sequence has no channel #" + c);

        final int sizeT = input.getSizeT();
        final int sizeX = input.getSizeX();
        final int sizeY = input.getSizeY();
        // int sizeC = input.getSizeC();
        final int sliceSize = sizeX * sizeY;

        final ArrayList<ROI> output = new ArrayList<>(sizeT);

        final DataType dataType = input.getDataType();

        for (int t = 0; t < sizeT; t++) {
            final double[] thresholds = thresholdsOverTime[t];

            if (thresholds == null || thresholds.length == 0)
                throw new IllegalArgumentException("Thresholder: no thresholds given");

            final int depth = input.getSizeZ(t);

            final int lastThresholdIndex = thresholds.length - 1;

            // initialize one mask per class
            final BooleanMask2D[][] masks = new BooleanMask2D[depth][thresholds.length];
            final double thr0 = thresholds[0];

            // keep a list of valid (non-empty slices)
            final boolean[] isValidSlice = new boolean[depth];

            for (int z = 0; z < depth; z++) {
                for (int thr = 0; thr < thresholds.length; thr++)
                    masks[z][thr] = new BooleanMask2D(new Rectangle(sizeX, sizeY), new boolean[sliceSize]);

                final BooleanMask2D[] masks2D = masks[z];

                final Object _in2D = input.getDataXY(t, z, c);

                withTheNextPixel:
                for (int i = 0; i < sliceSize; i++) {
                    final double val = Array1DUtil.getValue(_in2D, i, dataType);

                    // background
                    if (val < thr0)
                        continue;

                    // treat the n-class case first
                    if (lastThresholdIndex > 0) {
                        // browse thresholds from highest to lowest (above 1 to save one test)
                        // assign the first positive match and break the loop
                        for (int thr = lastThresholdIndex; thr > 0; thr--)
                            if (val >= thresholds[thr]) {
                                masks2D[thr].mask[i] = true;
                                if (!isValidSlice[z])
                                    isValidSlice[z] = true;
                                continue withTheNextPixel;
                            }
                    }

                    // last class (1 => index 0)
                    masks2D[0].mask[i] = true;
                    if (!isValidSlice[z])
                        isValidSlice[z] = true;
                }
            }

            for (int thr = 0; thr < thresholds.length; thr++) {
                ROI3DArea area3D = null;
                ROI2DArea area2D = null;

                for (int z = 0; z < depth; z++) {
                    if (!isValidSlice[z])
                        continue;

                    area2D = new ROI2DArea(masks[z][thr]);

                    if (depth > 1) {
                        if (area3D == null) {
                            area3D = new ROI3DArea();
                            area3D.setName("Threshold: " + thresholds[thr]);
                            area3D.setT(sizeT == 1 ? -1 : t);
                            // area3D.setC(c);
                        }
                        //area3D.setSlice(z, area2D, false);
                        area3D.setSlice(z, area2D);
                    }
                    else {
                        area2D.setName("Threshold: " + thresholds[thr]);
                        area2D.setT(sizeT == 1 ? -1 : t);
                        // area2D.setC(c);
                        // area2D.setZ(z);
                    }
                }

                if (area3D != null) {
                    output.add(area3D);
                }
                else if (area2D != null) {
                    output.add(area2D);
                }
            }
        }

        return output.toArray(new ROI[0]);
    }

    @Override
    public void clean() {
    }
}
