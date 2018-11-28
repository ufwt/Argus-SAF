/*
 * Copyright (c) 2018. Fengguo Wei and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License v2.0
 * which accompanies this distribution, and is available at
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Detailed contributors are listed in the CONTRIBUTOR.md
 */

package org.argus.jawa.flow.summary.wu

import org.argus.jawa.core.ast.ReturnStatement
import org.argus.jawa.core.elements.Signature
import org.argus.jawa.core.util._
import org.argus.jawa.core.{Global, JawaMethod}
import org.argus.jawa.flow.cfg._
import org.argus.jawa.flow.dfa.InterProceduralDataFlowGraph
import org.argus.jawa.flow.pta._
import org.argus.jawa.flow.pta.model.ModelCallHandler
import org.argus.jawa.flow.summary.store.{TSTaintPath, TaintStore}
import org.argus.jawa.flow.summary.susaf.rule._
import org.argus.jawa.flow.summary.{Summary, SummaryManager, SummaryRule}
import org.argus.jawa.flow.taintAnalysis._

class TaintWu[T <: Global](
    global: T,
    method: JawaMethod,
    sm: SummaryManager,
    handler: ModelCallHandler,
    ssm: SourceAndSinkManager[T],
    ts: TaintStore) extends DataFlowWu[T, TaintSummaryRule](global, method, sm, handler) {

  private def getTainted(cn: ICFGCallNode, poss: ISet[SSPosition], isSource: Boolean): ISet[Instance] = {
    val vars: MSet[String] = msetEmpty
    if(poss.isEmpty) {
      // Source means return value, sink means all poss
      if(isSource) {
        vars ++= cn.retNameOpt
      } else {
        vars ++= cn.argNames
      }
    } else {
      val inc = if(cn.getCallType == "static") 1 else 0
      poss.foreach { pos =>
        vars ++= cn.argNames.lift(pos.pos - inc)
      }
    }
    vars.flatMap { v =>
      ptaresult.getRelatedInstances(cn.getContext, VarSlot(v)) ++ ptaresult.getRelatedInstancesAfterCall(cn.getContext, VarSlot(v))
    }.toSet
  }

  object TaintStatus extends Enumeration {
    val TAINT, FAKE, PASS = Value
  }

  case class TaintInfo(status: TaintStatus.Value, pos: Option[SSPosition], path: IList[TaintNode])

  def getTaintInstance: ICFGNode => (IMap[Instance, TaintInfo], IMap[Instance, TaintInfo]) = {
    case en: ICFGEntryNode =>
      val srcInss: MMap[Instance, TaintInfo] = mmapEmpty
      val inc = if(method.isStatic) 1 else 0
      en.paramNames.indices foreach { idx =>
        val name = en.paramNames(idx)
        val inss = ptaresult.getRelatedInstances(en.getContext, VarSlot(name))
        val status = if(ssm.isEntryPointSource(global, en.getContext.getMethodSig) || ssm.isCallbackSource(global, en.getContext.getMethodSig, idx)) {
          TaintStatus.TAINT
        } else {
          TaintStatus.PASS
        }
        val info = TaintInfo(status, Some(new SSPosition(idx + inc)), ilistEmpty)
        srcInss ++= inss.map(ins => (ins, info))
      }
      (srcInss.toMap, imapEmpty)
    case cn: ICFGCallNode =>
      val srcInss: MMap[Instance, TaintInfo] = mmapEmpty
      val sinkInss: MMap[Instance, TaintInfo] = mmapEmpty
      // Handle method calls with generated summary.
      val callees = cn.getCalleeSet
      callees foreach { callee =>
        ssm.isSourceMethod(global, callee.callee) match {
          case Some((_, poss)) =>
            val info = TaintInfo(TaintStatus.TAINT, None, ilistEmpty)
            srcInss ++= getTainted(cn, poss, isSource = true).map(ins => (ins, info))
          case None =>
        }
        ssm.isSinkMethod(global, callee.callee) match {
          case Some((_, poss)) =>
            val info = TaintInfo(TaintStatus.TAINT, None, ilistEmpty)
            sinkInss ++= getTainted(cn, poss, isSource = false).map(ins => (ins, info))
          case None =>
        }

        sm.getSummary[TaintSummary](callee.callee) match {
          case Some(summary) =>
            summary.rules.foreach {
              case SourceSummaryRule(ins, path) =>
                val info = TaintInfo(TaintStatus.FAKE, None, path)
                srcInss += ((ins, info))
              case SinkSummaryRule(hb, path) =>
                val inss = getHeapInstance(hb, cn.retNameOpt, cn.retNameOpt, cn.argNames, cn.getContext)
                val info = TaintInfo(TaintStatus.FAKE, None, path)
                sinkInss ++= inss.map(ins => (ins, info))
              case _ =>
            }
          case None =>
        }
      }
      (srcInss.toMap, sinkInss.toMap)
    case nn: ICFGNormalNode =>
      val sinkInss: MMap[Instance, TaintInfo] = mmapEmpty
      val loc = method.getBody.resolvedBody.location(nn.locIndex)
      loc.statement match {
        case rs: ReturnStatement =>
          rs.varOpt match {
            case Some(ret) =>
              val inss = ptaresult.getRelatedInstances(nn.getContext, VarSlot(ret.varName))
              val info = TaintInfo(TaintStatus.PASS, None, ilistEmpty)
              sinkInss ++= inss.map(ins => (ins, info))
            case None =>
          }
        case _ =>
      }
      (imapEmpty, sinkInss.toMap)
    case _ =>
      (imapEmpty, imapEmpty)
  }

  def getAllInstances: ICFGNode => ISet[Instance] = {
    case cn: ICFGCallNode =>
      val allInss: MSet[Instance] = msetEmpty
      cn.retNameOpt match {
        case Some(rn) =>
          allInss ++= ptaresult.getRelatedInstancesAfterCall(cn.getContext, VarSlot(rn))
        case None =>
      }
      for (arg <- cn.argNames) {
        allInss ++= ptaresult.getRelatedInstances(cn.getContext, VarSlot(arg)) ++ ptaresult.getRelatedInstancesAfterCall(cn.getContext, VarSlot(arg))
      }
      allInss.toSet
    case _ =>
      isetEmpty
  }

  override def parseIDFG(idfg: InterProceduralDataFlowGraph): IList[TaintSummaryRule] = {
    var rules = super.parseIDFG(idfg)

    val srcInstances: MSet[(Instance, IList[TaintNode])] = msetEmpty
    val sinkHeapBases: MSet[(HeapBase, IList[TaintNode])] = msetEmpty

    def dfs(node: ICFGNode, taintInss: IMap[Instance, TaintInfo], paths: IMap[Instance, IList[TaintNode]]): Unit = {
      var newPaths: IMap[Instance, IList[TaintNode]] = paths
      // handle pass through
      val allInss = getAllInstances(node)
      allInss.intersect(taintInss.keys.toSet).foreach { ins =>
        newPaths += (ins -> (newPaths.getOrElse(ins, ilistEmpty) :+ TaintNode(node, None)))
      }
      // handle source and sink
      val (srcInss, sinkInss) = getTaintInstance(node)
//      println(node)
//      println("srcInss: " + srcInss)
//      println("sinkInss: " + sinkInss)
      sinkInss.foreach { case (sink, realsink) =>
        taintInss.get(sink) match {
          case Some(realsource) =>
            val sinkNode = TaintNode(node, realsink.pos)
            val path: IList[TaintNode] = paths(sink) :+ sinkNode
            val sourceNode = path.head
            realsource.status match {
              case TaintStatus.TAINT =>
                val taintSource = TaintSource(sourceNode, TypeTaintDescriptor(sourceNode.node.toString, realsource.pos, SourceAndSinkCategory.API_SOURCE))
                realsink.status match {
                  case TaintStatus.TAINT =>
                    val taintSink = TaintSink(sinkNode, TypeTaintDescriptor(node.toString, realsink.pos, SourceAndSinkCategory.API_SINK))
                    val tp = TSTaintPath(taintSource, taintSink)
                    tp.path = path
                    ts.addTaintPath(tp)
                  case TaintStatus.FAKE =>
                    val sinkPath = realsink.path
                    if(sinkPath.nonEmpty) {
                      val realSinkNode = sinkPath.last
                      val taintSink = TaintSink(realSinkNode, TypeTaintDescriptor(realSinkNode.node.toString, realSinkNode.pos, SourceAndSinkCategory.API_SINK))
                      val tp = TSTaintPath(taintSource, taintSink)
                      tp.path = path ++ sinkPath
                      ts.addTaintPath(tp)
                    }
                  case TaintStatus.PASS =>
                    srcInstances += ((sink, path))
                }
              case TaintStatus.FAKE =>
                val sourcePath = realsource.path
                if(sourcePath.nonEmpty) {
                  val realSourceNode = sourcePath.head
                  val taintSource = TaintSource(realSourceNode, TypeTaintDescriptor(realSourceNode.node.toString, realsource.pos, SourceAndSinkCategory.API_SOURCE))
                  realsink.status match {
                    case TaintStatus.TAINT =>
                      val taintSink = TaintSink(sinkNode, TypeTaintDescriptor(node.toString, realsink.pos, SourceAndSinkCategory.API_SINK))
                      val tp = TSTaintPath(taintSource, taintSink)
                      tp.path = sourcePath ++ path
                      ts.addTaintPath(tp)
                    case TaintStatus.FAKE =>
                      val sinkPath = realsink.path
                      if(sinkPath.nonEmpty) {
                        val realSinkNode = sinkPath.last
                        val taintSink = TaintSink(realSinkNode, TypeTaintDescriptor(realSinkNode.node.toString, realSinkNode.pos, SourceAndSinkCategory.API_SINK))
                        val tp = TSTaintPath(taintSource, taintSink)
                        tp.path = sourcePath ++ path ++ sinkPath
                        ts.addTaintPath(tp)
                      }
                    case TaintStatus.PASS =>
                      srcInstances += ((sink, sourcePath ++ path))
                  }
                }
              case TaintStatus.PASS =>
                realsink.status match {
                  case TaintStatus.TAINT =>
                    getInitialHeapBase(sink) match {
                      case Some(hb) =>
                        sinkHeapBases += ((hb, path))
                      case None =>
                    }
                  case TaintStatus.FAKE =>
                    getInitialHeapBase(sink) match {
                      case Some(hb) =>
                        sinkHeapBases += ((hb, path ++ realsink.path))
                      case None =>
                    }
                  case TaintStatus.PASS =>
                }
            }
          case None =>
        }
      }
      newPaths ++= srcInss.map { case (ins, info) =>
        val sourceNode = TaintNode(node, info.pos)
        info.status match {
          case TaintStatus.TAINT =>
            ins -> List(sourceNode)
          case TaintStatus.FAKE =>
            val sourcePath = info.path
            ins -> (sourcePath :+ sourceNode)
          case TaintStatus.PASS =>
            ins -> List(sourceNode)
        }
      }
      idfg.icfg.successors(node).foreach { succ =>
        dfs(succ, taintInss ++ srcInss, newPaths)
      }
    }

    // Update SourceSummaryRule
    dfs(idfg.icfg.entryNode, imapEmpty, imapEmpty)
    srcInstances foreach { case (srcIns, path) =>
      rules +:= SourceSummaryRule(srcIns, path)
    }
    // Update SinkSummaryRule
    sinkHeapBases foreach { case (hb, path) =>
      rules +:= SinkSummaryRule(hb, path)
    }
    rules
  }

  override def toString: String = s"TaintWu($method)"
}

case class TaintSummary(sig: Signature, rules: Seq[TaintSummaryRule]) extends Summary[TaintSummaryRule] {
  override def toString: FileResourceUri = {
    val sb = new StringBuilder
    rules.foreach { rule =>
      sb.append(sig.signature)
      sb.append(" -> ")
      sb.append(rule.toString)
      sb.append("\n")
    }
    sb.toString().trim
  }
}
trait TaintSummaryRule extends SummaryRule
case class SourceSummaryRule(ins: Instance, path: IList[TaintNode]) extends TaintSummaryRule {
  override def toString: FileResourceUri = "_SOURCE_"
}
case class SinkSummaryRule(hb: HeapBase, path: IList[TaintNode]) extends TaintSummaryRule {
  override def toString: FileResourceUri = {
    val sb = new StringBuilder
    sb.append("_SINK_ ")
    hb match {
      case _: SuThis =>
        sb.append("this")
      case a: SuArg =>
        sb.append(a.num)
      case g: SuGlobal =>
        sb.append(g.fqn)
    }
    hb.heapOpt match {
      case Some(heap) =>
        sb.append(heap.toString)
      case None =>
    }
    sb.toString()
  }
}