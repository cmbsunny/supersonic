--Agent
--tool_config 字段配置了 dataSetIds 列表
select * from s2_agent sa limit 10

--数据域
--id
select * from s2_domain sd limit 10

-- 数据集
--id, domain_id
--data_set_detail 内嵌Json，关联model、dimension、metric
select * from s2_data_set sds limit 10

-- 
--id, domain_id
select * from s2_model sm limit 10

--
--id,model_id
select * from s2_dimension sd limit 10

--
--id, model_id
select * from s2_metric sm limit 10



select * from memory_1 m limit 10