# AgentScope capability evaluations / AgentScope 能力评测

These 127 tasks are maintained directly by this project. Edit instructions, scenarios, fixtures and
assertions together; no external framework or generation step is required.
这 127 道题由本项目直接维护。修改时保持指令、场景、fixture 与断言一致，不依赖外部框架或生成步骤。

## References and attribution / 参考与归属

The initial scenarios and synthetic tools reference or adapt tests from
[DeepAgents](https://github.com/langchain-ai/deepagents) at commit `f7c3af67565f00894cb596f689d41bee10ae89af`.
Original portions remain under the [MIT license](LICENSE), copyright LangChain, Inc.
Runtime tool notices are also preserved in [LICENSE](../../runtime/fixtures/LICENSE).
The tasks are independently maintained AgentScope evaluations, not official upstream scores.
部分初始场景和模拟工具参考或改编自上述固定版本，保留原版权与 MIT 许可；后续由 AgentScope 独立维护，成绩不代表上游官方评测。

Repository examples use AgentScope names. The two large summarization source fixtures are static
reading inputs, not executable SDK modules; project-specific identifiers were normalized while
preserving line counts and the first standard-library import used by the retrieval assertion.
示例仓库使用 AgentScope 名称。两份大文本源码仅用于阅读和压缩测试，不作为 SDK 执行；
其中项目标识已统一，行数及检索断言所使用的首个标准库导入保持不变。

| Task / 题目 | Original test / 原始测试 | Variant / 变体 |
| --- | --- | --- |
| `cap-00f4b2b017c6` | [test_find_skill_in_correct_path](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_skills.py#L261) | 0 |
| `cap-03d41109da8d` | [test_glob_lists_markdown_files](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L530) | 0 |
| `cap-056932a87a5f` | [test_density_rank_lands_in_final_message](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_langchain_middleware_todo.py#L105) | 0 |
| `cap-07c5f0f859ec` | [test_multi_question_incident_oncall_and_incident_environment](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L884) | 0 |
| `cap-090819f63bd7` | [test_memory_does_not_persist_transient_info](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L228) | 0 |
| `cap-0bce9e0a61c5` | [test_five_tools_service_alert_names_parallel](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1119) | 0 |
| `cap-1185f315025b` | [test_two_tools_city_for_user](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L558) | 0 |
| `cap-119c9f88810b` | [test_four_steps_find_user_food_allergies](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L945) | 0 |
| `cap-1762cf35c5ee` | [test_memory_updates_user_formatting_preference](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L253) | 0 |
| `cap-1d39d14f6863` | [test_read_skill_full_content](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_skills.py#L40) | 0 |
| `cap-1f7050e140d4` | [test_multi_question_incident_oncall_and_service_with_most_firing_alerts](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L919) | 0 |
| `cap-1fc45ce67e01` | [test_memory_stale_fact_overridden_by_verified_file](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L361) | 0 |
| `cap-207635ca8b8c` | [test_write_then_delete_same_file](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L888) | 0 |
| `cap-21cee9fc7224` | [test_grep_wildcard_regex_recovers](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L468) | 0 |
| `cap-29dbcb11b16c` | [test_memory_influences_file_content](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L115) | 0 |
| `cap-29f282aca65a` | [test_combine_two_skills](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_skills.py#L122) | 0 |
| `cap-2a81f605d5b9` | [test_indirect_notify_team](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_selection.py#L223) | 0 |
| `cap-2abd7bb74468` | [test_read_file_empty_file_reports_empty](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L774) | 0 |
| `cap-2b2e542e5705` | [test_single_tool_list_incident_ids](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L786) | 0 |
| `cap-2b3aabc95b76` | [test_ls_directory_contains_file_yes_no](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L263) | 0 |
| `cap-2f0e00dde610` | [test_six_tools_incident_title_severity_and_status](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1203) | 0 |
| `cap-30cf5949e547` | [test_five_tools_service_dependency_names_parallel](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1091) | 0 |
| `cap-3295e8188b0d` | [test_memory_multiple_sources_combined](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L143) | 0 |
| `cap-3371eed25462` | [test_write_todos_three_steps_returns_text](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_todos.py#L53) | 0 |
| `cap-33e6021e52b0` | [test_indirect_email_report](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_selection.py#L251) | 0 |
| `cap-34f4b41f3ca7` | [test_two_tools_find_user_then_email](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L590) | 0 |
| `cap-358e91a254bf` | [test_delete_one_of_several_files](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L820) | 0 |
| `cap-3987f4c1b1f1` | [test_single_tool_list_user_ids](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L453) | 0 |
| `cap-3a754a674db7` | [test_latest_selection_active_incident_most_recent_deploy](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1354) | 0 |
| `cap-3b023ce693bd` | [test_avoid_unnecessary_tool_calls](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L358) | 0 |
| `cap-3c6d4d616c1f` | [test_multi_question_current_incident_service_and_incident_oncall](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L851) | 0 |
| `cap-3f40e2d02ddd` | [test_four_tools_incident_to_oncall_name](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L985) | 0 |
| `cap-40d4eafd1af3` | [test_ls_directory_missing_file_yes_no](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L287) | 0 |
| `cap-43e8088cf095` | [test_explicit_preference_remembered](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory_multiturn.py#L232) | explicit_timezone_preference |
| `cap-46d89576fe62` | [test_implicit_preference_remembered](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory_multiturn.py#L205) | implicit_language_preference |
| `cap-46e543a69bc8` | [test_five_steps_current_user_food_names_and_calories](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L847) | 0 |
| `cap-4dbd8de0148f` | [test_summarization_offloads_to_filesystem](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_summarization.py#L156) | 0 |
| `cap-4de208dbc1fd` | [test_followup_question_quality](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_followup_quality.py#L96) | vague_send_report |
| `cap-512dd7b7abe3` | [test_trivial_arithmetic_skips_write_todos](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_langchain_middleware_todo.py#L187) | 0 |
| `cap-5153b96c5b2e` | [test_transient_info_not_persisted](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory_multiturn.py#L259) | transient_weather_question |
| `cap-5291f73a2fd4` | [test_four_steps_find_user_email_city_foods_calories_and_allergies](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L1134) | 0 |
| `cap-54da59938a5a` | [test_read_files_in_parallel](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L376) | 0 |
| `cap-580c69528d0a` | [test_memory_prevents_unnecessary_file_reads](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L194) | 0 |
| `cap-58c4e73abcc9` | [test_metric_ranking_active_incident_highest_latency](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1410) | 0 |
| `cap-5cfb1300d586` | [test_four_tools_find_user_food_name_and_calories](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L761) | 0 |
| `cap-61860f93f637` | [test_delete_missing_file_reports_absence](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L918) | 0 |
| `cap-630b0f20fa38` | [test_three_tools_find_service_owner_team](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L829) | 0 |
| `cap-687bce510fa8` | [test_exact_word_count_and_z_starts](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_iterative_constraint_satisfaction.py#L168) | 0 |
| `cap-69c7afa7dd8c` | [test_three_tools_current_user_city](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L618) | 0 |
| `cap-6a3a27fb0349` | [test_multi_question_three_independent_simple_lookups](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L951) | 0 |
| `cap-6beabee04937` | [test_deletes_in_parallel](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L855) | 0 |
| `cap-7295e9da9d30` | [test_six_tools_service_repo_and_branch](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1177) | 0 |
| `cap-7483b2643b88` | [test_indirect_schedule_meeting](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_selection.py#L200) | 0 |
| `cap-750a11e859c3` | [test_chain_search_then_email](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_selection.py#L284) | 0 |
| `cap-7607648dc88b` | [test_direct_request_multiple_tools](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_selection.py#L168) | 0 |
| `cap-79068a78122c` | [test_memory_missing_file_graceful_without_claiming_context](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L286) | 0 |
| `cap-7b371d470cf2` | [test_custom_system_prompt](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_system_prompt.py#L29) | 0 |
| `cap-7b712c72c5a3` | [test_explicit_preference_remembered](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory_multiturn.py#L232) | explicit_no_emojis |
| `cap-7b83b991e503` | [test_five_tools_incident_latest_deploy_and_repo](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1030) | 0 |
| `cap-7bfc44dad4a7` | [test_identify_quote_author_from_directory_unprompted_efficiency](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L669) | 0 |
| `cap-7c3c14e39127` | [test_grep_alternation_regex_recovers](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L434) | 0 |
| `cap-7e926448e95a` | [test_rank_with_unknown_lookup_lands_in_final_message](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_langchain_middleware_todo.py#L222) | 0 |
| `cap-8048b051db56` | [test_aggregation_active_incident_count_by_team](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1261) | 0 |
| `cap-8538d18443ac` | [test_three_tools_find_user_then_city](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L652) | 0 |
| `cap-88cb345bb5f1` | [test_edit_file_replace_text](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L310) | 0 |
| `cap-890ddb0486da` | [test_read_file_remaining_lines_notice_enables_tail_jump](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L742) | 0 |
| `cap-8a0eb9c5d0d7` | [test_four_tools_service_runbook_url](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1008) | 0 |
| `cap-8b11059acced` | [test_trivial_plan_skips_write_todos](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_langchain_middleware_todo.py#L355) | 0 |
| `cap-8c6983e52bc5` | [test_memory_conflicting_identity_prefers_current_user](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L443) | 0 |
| `cap-90b13c272a29` | [test_dependency_reasoning_active_incident_depending_on_identity_api](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1518) | 0 |
| `cap-91aacce89570` | [test_memory_basic_recall](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L40) | 0 |
| `cap-91d67447589b` | [test_two_tools_current_incident_service_name](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L807) | 0 |
| `cap-93a8d9e8403b` | [test_write_files_in_parallel_ambiguous_confirmation](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L228) | 0 |
| `cap-95f3f7743bcb` | [test_single_tool_get_user_email](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L482) | 0 |
| `cap-984a6c6ee0c3` | [test_six_tools_current_incident_metrics_parallel](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1227) | 0 |
| `cap-989bdf03b04a` | [test_transient_info_not_persisted](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory_multiturn.py#L259) | transient_one_off_search |
| `cap-9eeb2f5487bd` | [test_delete_simple](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L791) | 0 |
| `cap-9f6dbdbf236e` | [test_followup_question_quality](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_followup_quality.py#L96) | detailed_calendar_brief |
| `cap-9f853917c45c` | [test_write_file_overwrites_existing](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L56) | 0 |
| `cap-a4e1566c8cb3` | [test_population_compare_lands_in_final_message](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_langchain_middleware_todo.py#L144) | 0 |
| `cap-a726c8faa5da` | [test_density_cairo_lands_in_final_message](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_langchain_middleware_todo.py#L321) | 0 |
| `cap-a7d6e76274c4` | [test_read_file_seeded_state_backend_file](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L36) | 0 |
| `cap-a870242d0bd5` | [test_four_tools_current_user_location_time_and_weather](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L802) | 0 |
| `cap-ab48f249207e` | [test_write_files_in_parallel_confirm_with_verification](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L193) | 0 |
| `cap-addf0caef10d` | [test_four_tools_current_user_favorite_food_names](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L720) | 0 |
| `cap-ae5c72e4b541` | [test_grep_finds_matching_paths](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L406) | 0 |
| `cap-aee074cb437d` | [test_comparison_active_incident_most_dependencies](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1304) | 0 |
| `cap-b49ab8d9d8a5` | [test_five_tools_incident_environment_name_and_region](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1059) | 0 |
| `cap-b715ed04e564` | [test_write_file_simple](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L139) | 0 |
| `cap-b91bb9c09fe5` | [test_read_then_write_derived_output](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L333) | 0 |
| `cap-b9e586d0409e` | [test_memory_with_missing_file_graceful](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L177) | 0 |
| `cap-bc06e5273de6` | [test_implicit_preference_remembered](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory_multiturn.py#L205) | implicit_working_hours |
| `cap-be1280edf7b7` | [test_direct_request_slack_dm](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_selection.py#L117) | 0 |
| `cap-c29cda3cd551` | [test_memory_user_explicit_request_overrides_saved_preference](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L415) | 0 |
| `cap-c2b204d3ae0d` | [test_transient_info_not_persisted](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory_multiturn.py#L259) | transient_mood |
| `cap-c59b50071b9f` | [test_memory_guided_behavior_naming_convention](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L72) | 0 |
| `cap-ca659b9c0e7a` | [test_write_file_overwrite_drops_old_content](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L85) | 0 |
| `cap-ccce3783925f` | [test_update_skill_typo_fix_requires_read](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_skills.py#L214) | 0 |
| `cap-ce130fdcfb27` | [test_single_tool_get_food_calories](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L506) | 0 |
| `cap-cf8047a9764b` | [test_grep_escaped_metachar_regex_recovers](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L499) | 0 |
| `cap-cfeda03fcce2` | [test_three_tools_current_user_weather](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L686) | 0 |
| `cap-d0df51aeaa45` | [test_task_calls_general_purpose_subagent](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_subagents.py#L79) | 0 |
| `cap-d13adc40acec` | [test_find_magic_phrase_deep_nesting](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L558) | 0 |
| `cap-d5dfee5a1bc9` | [test_followup_question_quality](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_followup_quality.py#L96) | vague_customer_support |
| `cap-d66ae477f9c4` | [test_four_steps_current_user_food_names_calories_and_allergies](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L998) | 0 |
| `cap-d673efe065bb` | [test_write_file_prefers_edit_for_targeted_change](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L109) | 0 |
| `cap-d747d66b561e` | [test_write_todos_sequential_updates_returns_text](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_todos.py#L27) | 0 |
| `cap-d75a3c8324ba` | [test_four_steps_find_user_city_and_weather](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L895) | 0 |
| `cap-e051b780e719` | [test_followup_question_quality](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_followup_quality.py#L96) | vague_monitor_system |
| `cap-e2812e246f22` | [test_chain_create_issue_then_notify](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_selection.py#L308) | 0 |
| `cap-e39afbcf098d` | [test_implicit_preference_remembered](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory_multiturn.py#L205) | implicit_domain_expertise |
| `cap-e4cd9e59683a` | [test_memory_investigation_precedes_memory_save_when_required](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L480) | 0 |
| `cap-e66060d27022` | [test_memory_adversarial_instruction_does_not_override_user](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L386) | 0 |
| `cap-ea5df1b9e920` | [test_explicit_preference_remembered](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory_multiturn.py#L232) | explicit_formal_language |
| `cap-ed4254b724f3` | [test_four_steps_find_user_city_weather_time_and_food_details](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L1063) | 0 |
| `cap-ed99b28e05ba` | [test_followup_question_quality](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_followup_quality.py#L96) | vague_summarize_emails |
| `cap-ee2707e3672a` | [test_two_tools_user_name_from_current_id](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_relational.py#L530) | 0 |
| `cap-ef77b144a671` | [test_six_tools_current_incident_oncall_name_and_email](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1147) | 0 |
| `cap-f0c0627e68ae` | [test_summarize_continues_task](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_summarization.py#L116) | 0 |
| `cap-f0fefc0fa48f` | [test_write_files_in_parallel](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L161) | 0 |
| `cap-f49537f31f95` | [test_design_api_lands_in_final_message](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_langchain_middleware_todo.py#L283) | 0 |
| `cap-f4bbe40226cd` | [test_read_skill_by_name](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_skills.py#L78) | 0 |
| `cap-f4d5710785ce` | [test_alert_aggregation_service_with_most_firing_alerts](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_usage_incident_graph.py#L1462) | 0 |
| `cap-fdb9b733969e` | [test_followup_question_quality](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_followup_quality.py#L96) | vague_data_analysis |
| `cap-fec401f08868` | [test_identify_quote_author_from_directory_parallel_reads](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_file_operations.py#L594) | 0 |
| `cap-fedb843ef373` | [test_direct_request_github_pr](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_tool_selection.py#L140) | 0 |
| `cap-ff4c34727b5e` | [test_update_skill_typo_fix_no_read](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_skills.py#L173) | 0 |

## Excluded reference cases / 未纳入的参考条目

The following 12 reference cases require contracts absent from the supported execution profiles.
They are documented here only and are not selected by any suite.
以下 12 个参考条目依赖当前执行配置没有的专属契约，仅在此记录，不作为可执行题目或套件选项。

| ID | Reference / 来源 | Reason / 原因 |
| --- | --- | --- |
| `cap-1b0c85509c57` | [original test](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_goal_tools.py#L107) | DeepAgents private goal/rubric state and canonical goal notices are product-specific contracts, absent in these native profiles. |
| `cap-4fe7f9d6956e` | [original test](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_goal_tools.py#L172) | DeepAgents private goal/rubric state and canonical goal notices are product-specific contracts, absent in these native profiles. |
| `cap-5f5fca54bf8c` | [original test](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_memory.py#L310) | LangGraph CompositeBackend/StoreBackend routing is not exposed by these native profiles; copying its answer into a file would not test the contract. |
| `cap-66ec1e8c93c7` | [original test](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_goal_tools.py#L204) | DeepAgents private goal/rubric state and canonical goal notices are product-specific contracts, absent in these native profiles. |
| `cap-922bca80d6ec` | [original test](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_subagents.py#L39) | Named weather_agent with a child-only tool allowlist is not configured in the pinned native profiles. |
| `cap-9e6fe4210a07` | [original test](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_goal_tools.py#L239) | DeepAgents private goal/rubric state and canonical goal notices are product-specific contracts, absent in these native profiles. |
| `cap-a0bde12c8b8f` | [original test](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_goal_tools.py#L138) | DeepAgents private goal/rubric state and canonical goal notices are product-specific contracts, absent in these native profiles. |
| `cap-b68588e39886` | [original test](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_goal_tools.py#L89) | DeepAgents private goal/rubric state and canonical goal notices are product-specific contracts, absent in these native profiles. |
| `cap-d5b0e38e684e` | [original test](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_summarization.py#L261) | Requires the model-invoked compact_conversation tool plus seeded native message state. Automatic compaction is not equivalent; current profiles lack this contract. |
| `cap-eb36b91f9649` | [original test](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_summarization.py#L229) | Requires the model-invoked compact_conversation tool plus seeded native message state. Automatic compaction is not equivalent; current profiles lack this contract. |
| `cap-f5d46dde69a1` | [original test](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_summarization.py#L245) | Requires the model-invoked compact_conversation tool plus seeded native message state. Automatic compaction is not equivalent; current profiles lack this contract. |
| `cap-f9ee56705e29` | [original test](https://github.com/langchain-ai/deepagents/blob/f7c3af67565f00894cb596f689d41bee10ae89af/libs/evals/tests/evals/test_auto_mode_authorization.py#L326) | DeepAgents Code AutoDecisionBatch classifier policy is a product-specific API, absent in these native profiles. |
