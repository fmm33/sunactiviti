package com.sunny.activiti.service.impl;

import cn.hutool.core.collection.IterUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sunny.activiti.common.entity.PageBean;
import com.sunny.activiti.common.util.CommonUtil;
import com.sunny.activiti.entity.FlowDef;
import com.sunny.activiti.entity.FlowMain;
import com.sunny.activiti.entity.FlowRule;
import com.sunny.activiti.entity.VacationOrder;
import com.sunny.activiti.mapper.FlowDefMapper;
import com.sunny.activiti.mapper.FlowMainMapper;
import com.sunny.activiti.mapper.FlowRuleMapper;
import com.sunny.activiti.service.IFlowInfoService;
import com.sunny.activiti.service.IVacationOrderService;
import lombok.extern.slf4j.Slf4j;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * @ClassName: FlowInfoServiceImpl
 * @Description:
 * @Author: sunt
 * @Date: 2020/6/1 10:30
 * @Version 1.0
 **/
@Service
@Slf4j
public class FlowInfoServiceImpl implements IFlowInfoService {

    @Autowired
    private FlowDefMapper flowDefMapper;
    @Autowired
    private FlowRuleMapper flowRuleMapper;
    @Autowired
    private FlowMainMapper flowMainMapper;
    @Autowired
    private IVacationOrderService vacationOrderService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;


    @Override
    public List<FlowDef> queryFlowDefList() {
        QueryWrapper<FlowDef> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("FLOW_STATE",0);
        return flowDefMapper.selectList(queryWrapper);
    }

    @Override
    public FlowDef queryFlowDef(Long defId) {
        QueryWrapper<FlowDef> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("DEF_ID",defId);
        return flowDefMapper.selectOne(queryWrapper);
    }

    @Transactional
    @Override
    public void insertFlowDef(FlowDef flowDef) {
        String flowCode = flowDef.getFlowCode();
        QueryWrapper<FlowDef> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("FLOW_CODE",flowCode);
        Integer count = flowDefMapper.selectCount(queryWrapper);
        if(count <= 0) {
            flowDef.setDefId(CommonUtil.genId());
            flowDef.setFlowState(0);
            flowDefMapper.insert(flowDef);
        }

    }

    @Override
    public String insertFlowRule(FlowRule flowRule) {
        String resMsg = "";
        QueryWrapper<FlowRule> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("DEF_ID",flowRule.getDefId());
        Integer count = flowRuleMapper.selectCount(queryWrapper);
        if(count <= 0) {
            flowRule.setRuleId(CommonUtil.genId());
            flowRule.setSystemCode(String.join(",",flowRule.getSystemIds()));
            flowRule.setBusiType(String.join(",",flowRule.getBusiTypes()));
            flowRuleMapper.insert(flowRule);
            return resMsg;
        }else {
            resMsg = "???????????????"+flowRule.getDefName()+"???????????????,???????????????????????????";
            return resMsg;
        }
    }

    @Override
    public Page<FlowRule> queryFlowRule(PageBean pageBean) {
        Page<FlowRule> page = new Page<>(pageBean.getPage(),pageBean.getLimit());
        Page<FlowRule> flowRulePage = flowRuleMapper.selectPage(page, null);
        return flowRulePage;
    }

    @Override
    public String resolve(Long orderId,Map<String, Object> variables) {
        //????????????????????????
        List<FlowRule> flowRules = flowRuleMapper.selectList(null);
        if(IterUtil.isEmpty(flowRules)) {
            throw new RuntimeException("????????????:"+orderId+",????????????????????????");
        }
        //?????????????????????
        VacationOrder vacationOrder = vacationOrderService.queryVacation(orderId);
        //????????????
        FlowRule currFlowRule = null;
        for (FlowRule flowRule : flowRules) {
            if(CommonUtil.checkKeywordsTrue(flowRule.getSystemCode(),vacationOrder.getSystemCode())
              && CommonUtil.checkKeywordsTrue(flowRule.getBusiType(),vacationOrder.getBusiType())) {
                log.info("-------->????????????????????????:{}",orderId);
                currFlowRule = flowRule;
                break;
            }
        }
        if(ObjectUtil.isNull(currFlowRule)) {
            log.info("?????????????????????");
            throw new RuntimeException("?????????????????????");
        }

        log.info("?????????:{},?????????????????????????????????:{}",orderId, JSONUtil.toJsonStr(currFlowRule));
        //????????????????????????
        FlowDef flowDef = this.queryFlowDef(currFlowRule.getDefId());
        //????????????????????????
        FlowMain flowMain = new FlowMain();
        flowMain.setOrderNo(orderId);
        flowMain.setFlowDefId(flowDef.getFlowCode());
        flowMain.setRuleId(currFlowRule.getRuleId());
        this.insertFlowMain(flowMain);
        //????????????
        String flowId = this.runFlow(flowMain, variables);
        return flowId;
    }

    @Override
    public String runFlow(FlowMain flowMain,Map<String, Object> variables) {
        String flowId = "";
        try {
            log.info("-------->??????????????????:{}",flowMain.getOrderNo());
            ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(flowMain.getFlowDefId(), String.valueOf(flowMain.getFlowInstId()), variables);
            flowId = processInstance.getProcessInstanceId();
            log.info("------>??????????????????flowId:{}",flowId);

            flowMain.setFlowId(Long.valueOf(flowId));
            flowMainMapper.updateById(flowMain);
        } catch (Exception e) {
            log.error("------------->??????????????????.....");
            e.printStackTrace();
        }
        return flowId;
    }

    @Override
    @Transactional
    public void insertFlowMain(FlowMain flowMain) {
        flowMain.setFlowInstId(CommonUtil.genId());
        flowMain.setFlowState(1);
        flowMain.setCreateTime(DateUtil.date());
        flowMainMapper.insert(flowMain);
    }

    @Override
    public Task queryTaskByInstId(String processInstanceId) {
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).active().singleResult();
        return task;
    }

    @Override
    public FlowMain queryFlowMainByOrderNo(Long orderNo) {
        QueryWrapper<FlowMain> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("ORDER_NO",orderNo);
        return flowMainMapper.selectOne(queryWrapper);
    }

    @Override
    public FlowMain queryFlowById(Long flowInstId) {
        QueryWrapper<FlowMain> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("FLOW_INST_ID",flowInstId);
        return flowMainMapper.selectOne(queryWrapper);
    }

}