/*
 * Copyright (c) 2010-2026. Institut Pasteur.
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

import fr.icy.extension.plugin.abstract_.Plugin;
import fr.icy.extension.plugin.annotation_.IcyPluginName;
import fr.icy.model.sequence.Sequence;
import plugins.adufour.blocks.lang.Block;
import plugins.adufour.blocks.util.VarList;
import plugins.adufour.vars.gui.model.IntegerRangeModel;
import plugins.adufour.vars.lang.VarDoubleArrayNative;
import plugins.adufour.vars.lang.VarInteger;
import plugins.adufour.vars.lang.VarSequence;
import plugins.adufour.vars.util.VarException;

@IcyPluginName("KMeans Threshold")
public class KMeansThresholdBlock extends Plugin implements Block {
    VarSequence input = new VarSequence("Input", null);
    VarInteger channel = new VarInteger("Channel", 0);
    VarInteger nbClasses = new VarInteger("Classes", 2);

    VarDoubleArrayNative thresholds = new VarDoubleArrayNative("thresholds", new double[]{});

    @Override
    public void run() {
        final Sequence seq = input.getValue();

        if (seq == null) throw new VarException(input, "Thresholder: no sequence selected");

        final int c = channel.getValue();
        final int sizeC = seq.getSizeC();

        if (c >= sizeC) throw new VarException(channel, "Thresholder: channel " + c + " does not exist");

        final int nC = nbClasses.getValue();

        final double[][] thrs = KMeans.computeKMeansThresholds(seq, nC);

        thresholds.setValue(thrs[c]);
    }

    @Override
    public void declareInput(final VarList inputMap) {
        nbClasses.setDefaultEditorModel(new IntegerRangeModel(2, 2, 65535, 1));
        channel.setDefaultEditorModel(new IntegerRangeModel(0, 0, 65535, 1));

        inputMap.add("Input", input);
        inputMap.add("Channel", channel);
        inputMap.add("Classes", nbClasses);
    }

    @Override
    public void declareOutput(final VarList outputMap) {
        outputMap.add("thresholds", thresholds);
    }

}
