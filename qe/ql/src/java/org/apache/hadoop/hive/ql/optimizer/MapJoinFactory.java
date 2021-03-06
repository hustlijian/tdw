/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.optimizer;

import java.io.Serializable;
import java.util.List;
import java.util.ArrayList;
import java.util.Stack;
import java.util.Map;
import java.util.HashMap;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.MultiHdfsInfo;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.lib.Node;
import org.apache.hadoop.hive.ql.lib.NodeProcessor;
import org.apache.hadoop.hive.ql.lib.NodeProcessorCtx;
import org.apache.hadoop.hive.ql.exec.MapJoinOperator;
import org.apache.hadoop.hive.ql.exec.SelectOperator;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.TaskFactory;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.OperatorFactory;
import org.apache.hadoop.hive.ql.exec.UnionOperator;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.parse.ErrorMsg;
import org.apache.hadoop.hive.ql.parse.ParseContext;
import org.apache.hadoop.hive.ql.optimizer.GenMRProcContext.GenMRUnionCtx;
import org.apache.hadoop.hive.ql.optimizer.GenMRProcContext.GenMapRedCtx;
import org.apache.hadoop.hive.ql.optimizer.GenMRProcContext.GenMRMapJoinCtx;
import org.apache.hadoop.hive.ql.optimizer.unionproc.UnionProcContext;
import org.apache.hadoop.hive.ql.plan.mapJoinDesc;
import org.apache.hadoop.hive.ql.plan.mapredWork;
import org.apache.hadoop.hive.ql.plan.PlanUtils;
import org.apache.hadoop.hive.ql.plan.tableDesc;
import org.apache.hadoop.hive.ql.plan.fileSinkDesc;
import org.apache.hadoop.hive.conf.HiveConf;

public class MapJoinFactory {

  public static int getPositionParent(MapJoinOperator op, Stack<Node> stack) {
    int pos = 0;
    int size = stack.size();
    assert size >= 2 && stack.get(size - 1) == op;
    Operator<? extends Serializable> parent = (Operator<? extends Serializable>) stack
        .get(size - 2);
    List<Operator<? extends Serializable>> parOp = op.getParentOperators();
    pos = parOp.indexOf(parent);
    assert pos < parOp.size();
    return pos;
  }

  public static class TableScanMapJoin implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {
      MapJoinOperator mapJoin = (MapJoinOperator) nd;
      GenMRProcContext ctx = (GenMRProcContext) procCtx;

      int pos = getPositionParent(mapJoin, stack);

      Map<Operator<? extends Serializable>, GenMapRedCtx> mapCurrCtx = ctx
          .getMapCurrCtx();
      GenMapRedCtx mapredCtx = mapCurrCtx.get(mapJoin.getParentOperators().get(
          pos));
      Task<? extends Serializable> currTask = mapredCtx.getCurrTask();
      mapredWork currPlan = (mapredWork) currTask.getWork();
      Operator<? extends Serializable> currTopOp = mapredCtx.getCurrTopOp();
      String currAliasId = mapredCtx.getCurrAliasId();
      Operator<? extends Serializable> reducer = mapJoin;
      HashMap<Operator<? extends Serializable>, Task<? extends Serializable>> opTaskMap = ctx
          .getOpTaskMap();
      Task<? extends Serializable> opMapTask = opTaskMap.get(reducer);

      ctx.setCurrTopOp(currTopOp);
      ctx.setCurrAliasId(currAliasId);
      ctx.setCurrTask(currTask);

      if (opMapTask == null) {
        assert currPlan.getReducer() == null;
        GenMapRedUtils.initMapJoinPlan(mapJoin, ctx, false, false, false, pos);
      } else {
        GenMapRedUtils.joinPlan(mapJoin, null, opMapTask, ctx, pos, false,
            false, false);
        currTask = opMapTask;
        ctx.setCurrTask(currTask);
      }

      mapCurrCtx.put(
          mapJoin,
          new GenMapRedCtx(ctx.getCurrTask(), ctx.getCurrTopOp(), ctx
              .getCurrAliasId()));
      return null;
    }
  }

  public static class ReduceSinkMapJoin implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {
      MapJoinOperator mapJoin = (MapJoinOperator) nd;
      GenMRProcContext opProcCtx = (GenMRProcContext) procCtx;

      mapredWork cplan = GenMapRedUtils.getMapRedWork();
      ParseContext parseCtx = opProcCtx.getParseCtx();
      Task<? extends Serializable> redTask = TaskFactory.get(cplan,
          parseCtx.getConf());
      Task<? extends Serializable> currTask = opProcCtx.getCurrTask();

      int pos = getPositionParent(mapJoin, stack);
      boolean local = (pos == ((mapJoinDesc) mapJoin.getConf())
          .getPosBigTable()) ? false : true;

      GenMapRedUtils.splitTasks(mapJoin, currTask, redTask, opProcCtx, false,
          local, pos);

      currTask = opProcCtx.getCurrTask();
      HashMap<Operator<? extends Serializable>, Task<? extends Serializable>> opTaskMap = opProcCtx
          .getOpTaskMap();
      Task<? extends Serializable> opMapTask = opTaskMap.get(mapJoin);

      if (opMapTask == null) {
        assert cplan.getReducer() == null;
        opTaskMap.put(mapJoin, currTask);
        opProcCtx.setCurrMapJoinOp(null);
      } else {
        GenMapRedUtils.joinPlan(mapJoin, currTask, opMapTask, opProcCtx, pos,
            false, false, false);
        currTask = opMapTask;
        opProcCtx.setCurrTask(currTask);
      }

      return null;
    }
  }

  public static class MapJoin implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {

      SelectOperator sel = (SelectOperator) nd;
      MapJoinOperator mapJoin = (MapJoinOperator) sel.getParentOperators().get(
          0);
      assert sel.getParentOperators().size() == 1;

      GenMRProcContext ctx = (GenMRProcContext) procCtx;
      ParseContext parseCtx = ctx.getParseCtx();

      List<MapJoinOperator> listMapJoinOps = parseCtx
          .getListMapJoinOpsNoReducer();

      if (listMapJoinOps.contains(mapJoin)) {
        ctx.setCurrAliasId(null);
        ctx.setCurrTopOp(null);
        Map<Operator<? extends Serializable>, GenMapRedCtx> mapCurrCtx = ctx
            .getMapCurrCtx();
        mapCurrCtx.put((Operator<? extends Serializable>) nd, new GenMapRedCtx(
            ctx.getCurrTask(), null, null));
        return null;
      }

      ctx.setCurrMapJoinOp(mapJoin);

      Task<? extends Serializable> currTask = ctx.getCurrTask();
      GenMRMapJoinCtx mjCtx = ctx.getMapJoinCtx(mapJoin);
      if (mjCtx == null) {
        mjCtx = new GenMRMapJoinCtx();
        ctx.setMapJoinCtx(mapJoin, mjCtx);
      }

      mapredWork mjPlan = GenMapRedUtils.getMapRedWork();
      Task<? extends Serializable> mjTask = TaskFactory.get(mjPlan,
          parseCtx.getConf());

      tableDesc tt_desc = PlanUtils.getIntermediateFileTableDesc(PlanUtils
          .getFieldSchemasFromRowSchema(mapJoin.getSchema(), "temporarycol"));

      Context baseCtx = parseCtx.getContext();
      MultiHdfsInfo multiHdfsInfo = ctx.getMultiHdfsInfo();
      String taskTmpDir = null;
      if (!multiHdfsInfo.isMultiHdfsEnable()) {
        taskTmpDir = baseCtx.getMRTmpFileURI();
      } else {
        taskTmpDir = baseCtx.getExternalTmpFileURI(new Path(multiHdfsInfo
            .getTmpHdfsScheme()).toUri());
      }

      mjCtx.setTaskTmpDir(taskTmpDir);
      mjCtx.setTTDesc(tt_desc);
      mjCtx.setRootMapJoinOp(sel);

      sel.setParentOperators(null);

      Operator<? extends Serializable> fs_op = OperatorFactory.get(
          new fileSinkDesc(taskTmpDir, tt_desc, parseCtx.getConf().getBoolVar(
              HiveConf.ConfVars.COMPRESSINTERMEDIATE)), mapJoin.getSchema());

      assert mapJoin.getChildOperators().size() == 1;
      mapJoin.getChildOperators().set(0, fs_op);

      List<Operator<? extends Serializable>> parentOpList = new ArrayList<Operator<? extends Serializable>>();
      parentOpList.add(mapJoin);
      fs_op.setParentOperators(parentOpList);

      currTask.addDependentTask(mjTask);

      ctx.setCurrTask(mjTask);
      ctx.setCurrAliasId(null);
      ctx.setCurrTopOp(null);

      Map<Operator<? extends Serializable>, GenMapRedCtx> mapCurrCtx = ctx
          .getMapCurrCtx();
      mapCurrCtx.put((Operator<? extends Serializable>) nd, new GenMapRedCtx(
          ctx.getCurrTask(), null, null));

      return null;
    }
  }

  public static class MapJoinMapJoin implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {
      MapJoinOperator mapJoin = (MapJoinOperator) nd;
      GenMRProcContext ctx = (GenMRProcContext) procCtx;

      ParseContext parseCtx = ctx.getParseCtx();
      MapJoinOperator oldMapJoin = ctx.getCurrMapJoinOp();
      assert oldMapJoin != null;
      GenMRMapJoinCtx mjCtx = ctx.getMapJoinCtx(mapJoin);
      if (mjCtx != null)
        mjCtx.setOldMapJoin(oldMapJoin);
      else
        ctx.setMapJoinCtx(mapJoin, new GenMRMapJoinCtx(null, null, null,
            oldMapJoin));
      ctx.setCurrMapJoinOp(mapJoin);

      int pos = getPositionParent(mapJoin, stack);

      Map<Operator<? extends Serializable>, GenMapRedCtx> mapCurrCtx = ctx
          .getMapCurrCtx();
      GenMapRedCtx mapredCtx = mapCurrCtx.get(mapJoin.getParentOperators().get(
          pos));
      Task<? extends Serializable> currTask = mapredCtx.getCurrTask();
      mapredWork currPlan = (mapredWork) currTask.getWork();
      String currAliasId = mapredCtx.getCurrAliasId();
      Operator<? extends Serializable> reducer = mapJoin;
      HashMap<Operator<? extends Serializable>, Task<? extends Serializable>> opTaskMap = ctx
          .getOpTaskMap();
      Task<? extends Serializable> opMapTask = opTaskMap.get(reducer);

      ctx.setCurrTask(currTask);

      if (opMapTask == null) {
        assert currPlan.getReducer() == null;
        GenMapRedUtils.initMapJoinPlan(mapJoin, ctx, true, false, false, pos);
      } else {
        GenMapRedUtils.joinPlan(mapJoin, currTask, opMapTask, ctx, pos, false,
            true, false);
        currTask = opMapTask;
        ctx.setCurrTask(currTask);
      }

      mapCurrCtx.put(mapJoin, new GenMapRedCtx(ctx.getCurrTask(), null, null));
      return null;
    }
  }

  public static class UnionMapJoin implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {
      GenMRProcContext ctx = (GenMRProcContext) procCtx;

      ParseContext parseCtx = ctx.getParseCtx();
      UnionProcContext uCtx = parseCtx.getUCtx();

      if (uCtx.isMapOnlySubq())
        return (new TableScanMapJoin())
            .process(nd, stack, procCtx, nodeOutputs);

      UnionOperator currUnion = ctx.getCurrUnionOp();
      assert currUnion != null;
      GenMRUnionCtx unionCtx = ctx.getUnionTask(currUnion);
      MapJoinOperator mapJoin = (MapJoinOperator) nd;

      int pos = getPositionParent(mapJoin, stack);

      Map<Operator<? extends Serializable>, GenMapRedCtx> mapCurrCtx = ctx
          .getMapCurrCtx();
      GenMapRedCtx mapredCtx = mapCurrCtx.get(mapJoin.getParentOperators().get(
          pos));
      Task<? extends Serializable> currTask = mapredCtx.getCurrTask();
      mapredWork currPlan = (mapredWork) currTask.getWork();
      Operator<? extends Serializable> reducer = mapJoin;
      HashMap<Operator<? extends Serializable>, Task<? extends Serializable>> opTaskMap = ctx
          .getOpTaskMap();
      Task<? extends Serializable> opMapTask = opTaskMap.get(reducer);

      boolean local = (pos == ((mapJoinDesc) mapJoin.getConf())
          .getPosBigTable()) ? false : true;
      if (local)
        throw new SemanticException(ErrorMsg.INVALID_MAPJOIN_TABLE.getMsg());

      if (opMapTask == null) {
        assert currPlan.getReducer() == null;
        ctx.setCurrMapJoinOp(mapJoin);
        GenMapRedUtils.initMapJoinPlan(mapJoin, ctx, true, true, false, pos);
        ctx.setCurrUnionOp(null);
      } else {
        Task<? extends Serializable> uTask = ctx.getUnionTask(
            ctx.getCurrUnionOp()).getUTask();
        if (uTask.getId().equals(opMapTask.getId()))
          GenMapRedUtils.joinPlan(mapJoin, null, opMapTask, ctx, pos, false,
              false, true);
        else
          GenMapRedUtils.joinPlan(mapJoin, uTask, opMapTask, ctx, pos, false,
              false, true);
        currTask = opMapTask;
        ctx.setCurrTask(currTask);
      }

      mapCurrCtx.put(
          mapJoin,
          new GenMapRedCtx(ctx.getCurrTask(), ctx.getCurrTopOp(), ctx
              .getCurrAliasId()));
      return null;
    }
  }

  public static NodeProcessor getTableScanMapJoin() {
    return new TableScanMapJoin();
  }

  public static NodeProcessor getUnionMapJoin() {
    return new UnionMapJoin();
  }

  public static NodeProcessor getReduceSinkMapJoin() {
    return new ReduceSinkMapJoin();
  }

  public static NodeProcessor getMapJoin() {
    return new MapJoin();
  }

  public static NodeProcessor getMapJoinMapJoin() {
    return new MapJoinMapJoin();
  }
}
