/*
 * chombo: Hadoop Map Reduce utility
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */


package org.chombo.distance;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.chombo.util.BasicUtils;
import org.chombo.util.Pair;
import org.chombo.util.RichAttribute;
import org.chombo.util.RichAttributeSchema;

/**
 * @author pranab
 *
 */
public class InterRecordDistance implements Serializable {
	private RichAttributeSchema attrSchema;
	private AttributeDistanceSchema attrDistSchema;
	private String fieldDelim;
	private String subFieldDelim = BasicUtils.DEF_SUB_FIELD_DELIM;
	private String[] firstItems;
	private String[] secondItems;
	private String firstItem;
	private String secondItem;
	private int ordinal;
	private Map<Integer, Double> attrDistances = new HashMap<Integer, Double>();
	private Map<Integer, DynamicVectorSimilarity> textSimilarityStrategies = 
			new HashMap<Integer, DynamicVectorSimilarity>();
	private boolean doubleRange;
	
	/**
	 * @param attrSchema
	 * @param attrDistSchema
	 * @param fieldDelim
	 */
	public InterRecordDistance(RichAttributeSchema attrSchema,
			AttributeDistanceSchema attrDistSchema, String fieldDelim) {
		this.attrSchema = attrSchema;
		this.attrDistSchema = attrDistSchema;
		this.fieldDelim = fieldDelim;
	}
	
	/**
	 * @param subFieldDelim
	 * @return
	 */
	public InterRecordDistance withSubFieldDelim(String subFieldDelim) {
		this.subFieldDelim = subFieldDelim;
		return this;
	}
	
	/**
	 * @param doubleRange
	 * @return
	 */
	public InterRecordDistance withDoubleRange(boolean doubleRange) {
		this.doubleRange = doubleRange;
		return this;
	}
	
	/**
	 * @param first
	 * @param second
	 * @return
	 * @throws IOException 
	 */
	public double findDistance(String first, String second ) throws IOException {
		double recDist = 0;
		double dist = 0;
		attrDistances.clear();
		firstItems = first.split(fieldDelim);
		secondItems = second.split(fieldDelim);
		
		//attribute pair distances
		for (RichAttribute richAttr : attrSchema.getAttributes()) {
			if (richAttr.isId())
				continue;
			
			ordinal = richAttr.getOrdinal();
			AttributeDistance attrDist = attrDistSchema.findAttributeDistanceByOrdinal(ordinal);
			firstItem = firstItems[ordinal];
			secondItem = secondItems[ordinal];
			
			if (richAttr.isCategorical()) {
				dist = categoricalDistance(richAttr, attrDist);
			} else if (richAttr.isInteger()) {
				dist = numericDistance(Integer.parseInt(firstItem), Integer.parseInt(secondItem), attrDist);
			} else if (richAttr.isDouble()) {
				if (doubleRange) {
					DoubleRange firstItemRange = DoubleRange.create(firstItem, subFieldDelim);
					if (null != firstItemRange) {
						dist = numericDistance(firstItemRange,  Double.parseDouble(secondItem),  attrDist);
					} else {
						DoubleRange secondItemRange = DoubleRange.create(secondItem, subFieldDelim);
						if (null != secondItemRange) {
							dist = numericDistance(secondItemRange,  Double.parseDouble(firstItem),  attrDist);
						} else {
							throw new IllegalStateException("no range data found in field");
						}
					}
				} else {
					dist = numericDistance(Double.parseDouble(firstItem), Double.parseDouble(secondItem), attrDist);
				}
			} else if (richAttr.isText()) {
				dist = textDistance(attrDist);
			} else if (richAttr.isGeoLocation()) {
				dist = geoLocationDistance(attrDist);
			}
			
			attrDistances.put(ordinal, dist);
		}
		
		//aggregate
		double sumDist = 0;
		double sumWeight = 0;
		for (AttributeDistanceAggregator aggregator :  attrDistSchema.getAttrAggregators()) {
			if (aggregator.getAlgorithm().equals("euclidean")) {
				dist = aggregateEuclidean(aggregator.getOrdinals());
			} else if (aggregator.getAlgorithm().equals("manhattan")) {
				dist = aggregateManhattan(aggregator.getOrdinals());
			} else if (aggregator.getAlgorithm().equals("minkwoski")) {
				dist = aggregateMinkwoski(aggregator.getOrdinals(), aggregator.getParam());
			} else if (aggregator.getAlgorithm().equals("categorical")) {
				dist = aggregateCategorical(aggregator.getOrdinals());
			}
			sumDist += dist * aggregator.getWeight();
			sumWeight += aggregator.getWeight();
			
		}
		recDist = sumDist / sumWeight;
		return recDist;
	}
	
	/**
	 * @param richAttr
	 * @param attrDist
	 * @return
	 */
	private double categoricalDistance(RichAttribute richAttr, AttributeDistance attrDist) {
		double dist = 0;
		if (attrDist.getAlgorithm().equals("cardinality")) {
			dist = firstItem.equals(secondItem) ? 0 : Math.sqrt(2) / richAttr.getCardinality().size();
		} else {
			dist = firstItem.equals(secondItem) ? 0 : 1;
		}
		return dist;
	}
	
	/**
	 * @param firstItemDbl
	 * @param secondItemDbl
	 * @param attrDist
	 * @return
	 */
	private double numericDistance(double firstItemDbl, double secondItemDbl, AttributeDistance attrDist) {
		double dist = 0;
		dist = Math.abs(firstItemDbl - secondItemDbl);
		
		//apply weight
		if (attrDist.isWeightSet()) {
			dist /= attrDist.getWeight();
		}
		
		//apply threshold
		if (attrDist.isUpperThresholdSet() && dist > attrDist.getUpperThreshold()) {
			dist = 1;
		} else if (attrDist.isLowerThresholdSet() && dist > attrDist.getLowerThreshold()) {
			dist = 0;
		}
		return dist;
	}
	
	/**
	 * @param firstItemDblRange
	 * @param secondItemDbl
	 * @param attrDist
	 * @return
	 */
	private double numericDistance(DoubleRange firstItemDblRange, double secondItemDbl, AttributeDistance attrDist) {
		double dist = 0;
		if (secondItemDbl > firstItemDblRange.getUpper()) {
			dist = numericDistance(firstItemDblRange.getUpper(), secondItemDbl,  attrDist);
		} else if (secondItemDbl < firstItemDblRange.getLower()) {
			dist = numericDistance(secondItemDbl, firstItemDblRange.getLower(), attrDist);
		}
		return dist;
	}
	
	/**
	 * @param attrDist
	 * @return
	 * @throws IOException 
	 */
	private double textDistance(AttributeDistance attrDist) throws IOException {
		DynamicVectorSimilarity simStrategy = textSimilarityStrategies.get(ordinal);
		if (null == simStrategy) {
			simStrategy = DynamicVectorSimilarity.createSimilarityStrategy(attrDist);
			textSimilarityStrategies.put(ordinal, simStrategy);
		}
		return simStrategy.findDistance(firstItem, secondItem);
	}
	
	/**
	 * @param attrDist
	 * @return
	 */
	private double geoLocationDistance(AttributeDistance attrDist) {
    	String[] items = firstItem.split(subFieldDelim);
    	double lat1 = Double.parseDouble(items[0]);
    	double long1 = Double.parseDouble(items[1]);
    	
    	items = secondItem.split(":");
    	double lat2 = Double.parseDouble(items[0]);
    	double long2 = Double.parseDouble(items[1]);
		double dist = BasicUtils.getGeoDistance(lat1, long1, lat2, long2);
		if (attrDist.isMaxGeoDistanceSet()) {
			dist /= attrDist.getMaxGeoDistance();
		}
		
		return dist;
	}
	
	/**
	 * @param ordinals
	 * @return
	 */
	private double aggregateEuclidean(int[] ordinals) {
		double dist = 0;
		double sum = 0;
		for (int ordinal : ordinals) {
			sum += attrDistances.get(ordinal) * attrDistances.get(ordinal);
		}
		dist = Math.sqrt(sum) / ordinals.length;
		return dist;
	}
	
	/**
	 * @param ordinals
	 * @return
	 */
	private double aggregateManhattan(int[] ordinals) {
		double dist = 0;
		double sum = 0;
		for (int ordinal : ordinals) {
			sum += attrDistances.get(ordinal);
		}
		dist = sum / ordinals.length;
		return dist;
	}
	
	/**
	 * @param ordinals
	 * @return
	 */
	private double aggregateMinkwoski(int[] ordinals, double param) {
		double dist = 0;
		double sum = 0;
		for (int ordinal : ordinals) {
			Math.pow(attrDistances.get(ordinal), param);
			sum += Math.pow(attrDistances.get(ordinal), param);
		}
		
		dist = Math.pow(sum, 1.0/param) / ordinals.length;
		return dist;
	}

	/**
	 * @param ordinals
	 * @return
	 */
	private double aggregateCategorical(int[] ordinals) {
		double dist = 0;
		double sum = 0;
		for (int ordinal : ordinals) {
			sum += attrDistances.get(ordinal);
		}
		dist = sum / ordinals.length;
		return dist;
	}
	
	/**
	 * @author pranab
	 *
	 */
	private static class DoubleRange extends Pair<Double, Double> {
		
		/**
		 * @param first
		 * @param second
		 */
		public DoubleRange(Double first, Double second) {
			super(first,  second);
		}
		
		/**
		 * @return
		 */
		public double getLower() {
			return getLeft();
		}
		
		/**
		 * @return
		 */
		public double getUpper() {
			return getRight();
		}

		/**
		 * @param field
		 * @param subFieldDelim
		 * @return
		 */
		public static DoubleRange create(String field, String subFieldDelim) {
			DoubleRange doubleRange = null;
			String[] items = field.split(subFieldDelim);
			if (items.length == 2) {
				doubleRange = new DoubleRange(Double.parseDouble(items[0]), Double.parseDouble(items[1]));
			} else if (items.length != 1){
				throw new IllegalStateException("too many sub fields");
			}
			
			return doubleRange;
		}
		
	}
}
