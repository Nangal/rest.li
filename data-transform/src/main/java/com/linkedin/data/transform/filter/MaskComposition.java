/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

/**
 * $id$
 */
package com.linkedin.data.transform.filter;

import static com.linkedin.data.transform.filter.FilterUtil.getIntegerWithDefaultValue;
import static com.linkedin.data.transform.filter.FilterUtil.isMarkedAsMergedWith1;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import com.linkedin.data.DataMap;
import com.linkedin.data.transform.Instruction;
import com.linkedin.data.transform.Interpreter;
import com.linkedin.data.transform.InterpreterContext;

/**
 * This interpreter performs masks composition. Both data and operation are treated as
 * masks. After processing is finished, the data contains composition of masks and
 * operation is not modified. This means that data from instruction is modified during processing.
 *
 * @author jodzga
 *
 */
public class MaskComposition implements Interpreter
{

  @Override
  public void interpret(InterpreterContext instrCtx)
  {

    Instruction instruction = instrCtx.getCurrentInstruction();

    if ((!(instruction.getData().getClass() == DataMap.class))
        || (!(instruction.getOperation().getClass() == DataMap.class)))
    {
      instrCtx.addErrorMessage("data and operation in composition instruction have to be of type DataMap, instruction: %1$s",
                      instruction);
    }
    else
    {

      DataMap data = (DataMap) instruction.getData();
      DataMap op = (DataMap) instruction.getOperation();

      Object opWildcard = op.get(FilterConstants.WILDCARD);
      Object dataWildcard = data.get(FilterConstants.WILDCARD);

      if ((opWildcard != null && opWildcard.equals(FilterConstants.NEGATIVE))
          || (dataWildcard != null && dataWildcard.equals(FilterConstants.NEGATIVE)))
      {
        handleNegativeWildcard(data);
      }
      else
      {
          //process array range
          composeArrayRange(data, op, instrCtx);

          // process all fields
          for (Entry<String, Object> entry : op.entrySet())
          {
            String fieldName = entry.getKey();
            Object opMask = entry.getValue();
            Object dataMask = data.get(fieldName);

            if (!fieldName.equals(FilterConstants.START) && !fieldName.equals(FilterConstants.COUNT))
            {
              composeField(fieldName,
                           opMask,
                           dataMask,
                           data,
                           dataWildcard,
                           instrCtx);
            }
          }
      }
    }
  }

  /**
   * Compose values for array ranges and store the result in the data.
   * Rules for composition of array ranges are the following:
   * <ul>
   * <li>if start is not defined, then it is assumed to be 0</li>
   * <li>if count is not defined, then it is assumed the be the {@link Integer#MAX_VALUE}</li>
   * <li>result of start composition is <code>min(start1, start2)</code></li>
   * <li>result of count composition is <code>max(start1 + count1, start2 + count2) - min(start1, start2)</code></li>
   * </ul>
   * Values: 0 for start and {@link Integer#MAX_VALUE} for count are not stored explicitly.
   */
  protected void composeArrayRange(final DataMap data, final DataMap op, InterpreterContext instrCtx)
  {
    // otherwise ranges need to be merged
    final Integer startData = getIntegerWithDefaultValue(data, FilterConstants.START, 0);
    if (startData == null)
      addValueTypeNotIntegerError(data, FilterConstants.START, instrCtx);
    final Integer startOp = getIntegerWithDefaultValue(op, FilterConstants.START, 0);
    if (startOp == null)
      addValueTypeNotIntegerError(op, FilterConstants.START, instrCtx);
    final Integer countData = getIntegerWithDefaultValue(data, FilterConstants.COUNT, Integer.MAX_VALUE);
    if (countData == null)
      addValueTypeNotIntegerError(data, FilterConstants.COUNT, instrCtx);
    final Integer countOp = getIntegerWithDefaultValue(op, FilterConstants.COUNT, Integer.MAX_VALUE);
    if (countOp == null)
      addValueTypeNotIntegerError(op, FilterConstants.COUNT, instrCtx);

    if (startData < 0)
      addNegativeIntegerError(data, FilterConstants.START, startData, instrCtx);
    if (startOp < 0)
      addNegativeIntegerError(op, FilterConstants.START, startOp, instrCtx);
    if (countData < 0)
      addNegativeIntegerError(data, FilterConstants.COUNT, countData, instrCtx);
    if (countOp < 0)
      addNegativeIntegerError(op, FilterConstants.COUNT, countOp, instrCtx);


    if (startData != null && startOp != null && countData != null && countOp != null &&
        startData >= 0 && startOp >= 0 && countData >= 0 && countOp >= 0)
    {
      final Integer start = Math.min(startData, startOp);
      final Integer count = Math.max(startData + countData, startOp + countOp) - start;
      storeNonDefaultValue(data, FilterConstants.START, 0, start);
      storeNonDefaultValue(data, FilterConstants.COUNT, Integer.MAX_VALUE, count);
    }

  }

  private void addNegativeIntegerError(DataMap data, String fieldName, Integer value, InterpreterContext instrCtx)
  {
    instrCtx.addErrorMessage("value %1$s must be positive but is equal to %2$d",
                    fieldName, value);
  }

  private void addValueTypeNotIntegerError(final DataMap data, String fieldName, InterpreterContext instrCtx)
  {
    instrCtx.addErrorMessage("value should be of type Integer, but is of type: %1$s",
                    data.get(fieldName).getClass().getName());
  }

  /**
   * Stores value into the DataMap under given tag if value is different than default value. If value
   * is equal to default value, the tag is removed from the DataMap.
   */
  protected void storeNonDefaultValue(DataMap data, String tag, final Integer defaultValue, final Integer value)
  {
    if (value.equals(defaultValue))
      data.remove(tag);
    else
      data.put(tag, value);

  }

  /**
   * Clones DataMap and returns clone. If there was an error during cloning, then
   * null is returned.
   */
  private DataMap cloneDataMap(DataMap data, InterpreterContext instrCtx)
  {
    try
    {
      // we don't modify the operation side of data so use clone instead
     return  data.copy();
    }
    catch (CloneNotSupportedException e)
    {
      instrCtx.addErrorMessage("could not clone mask: %1$s, exception: %2$s",
                      data, e);
      return null;
    }
  }

  /**
   * Result of composition with mask containing $*=0 is simply $*=0. This method
   * clears DataMap and puts entry $*=0 into it.
   */
  private void handleNegativeWildcard(DataMap data)
  {
    // if one of masks to compose has $*=0, then case is very simple
    data.clear();
    data.put(FilterConstants.WILDCARD, FilterConstants.NEGATIVE);
  }

  /**
   * This method performs merging with mask = 1. It first tries to put $*=1 into the
   * mask. If wildcard already exists in the mask and is different than 1, then this
   * wildcard is recursively merged with 1. Additionally all simple masks (field=1) and
   * array ranges are removed from the mask, because they are redundant. Finally all
   * complex sub-masks (field={ ... }) are recursively merged with 1.
   *
   * @return returns true if mask was simplified and value in parent was replaced with 1
   */
  boolean mergeWith1(DataMap mask, DataMap parent, String key)
  {
    //precondition:
    assert mask != null;

    //since mask is being merged with 1, all existing
    //positive mask is redundant
    prunePositiveMask(mask);

    //first try to set *=1
    Object wildcard = mask.get(FilterConstants.WILDCARD);
    if (wildcard == null)
    {
      mask.put(FilterConstants.WILDCARD, FilterConstants.POSITIVE);
      wildcard = FilterConstants.POSITIVE;
    }
    else
    {
      //if wildcard is already 1, then nothing to do
      if (!wildcard.equals(FilterConstants.POSITIVE))
        //otherwise recursively mark it as merged with 1
        mergeWith1((DataMap)wildcard, mask, FilterConstants.WILDCARD);
    }

    //if the mask looks like: { $*: 1 }, then try to replace it with 1 in the parent
    if (mask.size() == 1 && wildcard != null && wildcard.equals(FilterConstants.POSITIVE))
    {
      parent.put(key, FilterConstants.POSITIVE);
      return true;
    }
    else
      return false;
  }

  /**
   * Removes array ranges from mask.
   * @param data
   */
  private void removeArrayRenges(DataMap data)
  {
    data.remove(FilterConstants.START);
    data.remove(FilterConstants.COUNT);
  }

  /**
   * This method handles logic for single field composition.
   * @return true if operation was successful and false otherwise
   */
  private boolean composeField(final String fieldName,
                            /**
                             * opMask is not final, because operation side of instruction should not be
                             * changed, so whenever it has to be merged with 1, clone is created
                             */
                            Object opMask,
                            final Object dataMask,
                            final DataMap data,
                            final Object dataWildcard,
                            InterpreterContext instrCtx)
  {
    instrCtx.setCurrentField(fieldName);

  boolean failed = false;
  if (dataMask == null)
    {
      // avoid copying 1 if there exist wildcard with value 1
      if (!opMask.equals(FilterConstants.POSITIVE) || !isMarkedAsMergedWith1(data))
        // missing fields are copied
        data.put(fieldName, opMask);
    }
    else if (dataMask instanceof Integer)
    {
      //if mask is negative, then there is no need for further merging
      //if it is positive, then
      if (((Integer)dataMask).equals(FilterConstants.POSITIVE))
      {
        if (opMask instanceof Integer)
        {
          Integer merged = merge((Integer) dataMask, (Integer) opMask, instrCtx);
          if (merged != null)
            data.put(fieldName, merged);
          else
            failed = true;
        }
        else if (opMask.getClass() == DataMap.class)
        {
          opMask = cloneDataMap((DataMap)opMask, instrCtx);
          if (opMask != null)
          {
            if (!mergeWith1((DataMap)opMask, data, fieldName))
              data.put(fieldName, opMask);
          }
          else
            failed = true;
        }
        else
        {
          instrCtx.addErrorMessage("field mask value of unsupported type: %1$s", opMask.getClass().getName());
          failed = true;
        }
      }
    }
    else if (dataMask.getClass() == DataMap.class)
    {
      if (opMask instanceof Integer)
      {
        if (((Integer)opMask).equals(FilterConstants.NEGATIVE))
          data.put(fieldName, FilterConstants.NEGATIVE);
        else
          mergeWith1((DataMap)dataMask, data, fieldName);
      }
      else if (opMask.getClass() == DataMap.class)
        instrCtx.scheduleInstruction(new Instruction((DataMap) opMask,
                                         (DataMap) dataMask,instrCtx.getPath()));
      else
      {
        instrCtx.addErrorMessage("field mask value of unsupported type: %1$s", opMask.getClass().getName());
        failed = true;
      }
    } else
      {
      instrCtx.addErrorMessage("field mask value of unsupported type: %1$s", dataMask.getClass().getName());
      failed = true;
      }

    //return true if operation was successful and false otherwise
    return !failed;
  }

  private boolean isValidMaskValue(Integer v, InterpreterContext ic)
  {
    if (v < 0 || v > 1)
    {
      ic.addErrorMessage("mask value have to be 0 or 1, but is: %1$s", v);
      return false;
    }
    return true;
  }

  /**
   * Returns the value of merge of simple masks. If values of simple
   * masks are not invalid then null is returned.
   */
  private Integer merge(Integer m1, Integer m2, InterpreterContext instrCtx)
  {
    if (isValidMaskValue(m1, instrCtx) && isValidMaskValue(m2, instrCtx))
      // negative mask takes precedence over positive mask
        return (m1 < m2) ? m1 : m2;
    else
      return null;
  }

  /**
   * This method removes all simple positive masks (field=1) from a complex mask.
   */
  private void prunePositiveMask(final DataMap complex)
  {
    removeArrayRenges(complex);
    final List<String> toBeRemoved = new ArrayList<String>();
    for (Entry<String, Object> entry : complex.entrySet())
    {
      Object v = entry.getValue();
      if (v.equals(FilterConstants.POSITIVE))
        toBeRemoved.add(entry.getKey());
      if (v.getClass() == DataMap.class)
      {
        //recursively prune mask
        prunePositiveMask((DataMap)v);
        if (((DataMap) v).size() == 0)
          toBeRemoved.add(entry.getKey());
      }
    }
    for (String name : toBeRemoved)
      complex.remove(name);
  }

}