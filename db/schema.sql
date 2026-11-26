-- ============================================================
-- refine-loop 全量建表脚本（SQLite 3，嵌入式单文件库）
-- 范围：41 张 Tushare 数据表 + 20 张业务表，共 61 张表 + 74 个索引（49 唯一 + 25 普通）。
-- 命名与结构规范见 docs/开发规范/02-数据库与SQL规范.md，由 schema/SchemaConventionTest 校验。
-- 说明：
--   1. 数据库引擎为 SQLite（sqlite-jdbc），不再需要 MySQL 服务进程；库文件默认
--      ./data/refine_loop.db，应用启动时由 SqliteSchemaInitializer 自动建表（幂等）。
--   2. 全部语句使用 IF NOT EXISTS，可在已有库上反复执行（只补缺表/索引，不改已有表结构）；
--      开发期结构变更仍是「删库文件重建」，不做迁移脚本。
--   3. 唯一约束严格按《Tushare接口对接手册》附录 C 幂等键（index_weight 修正为
--      index_code+trade_date+con_code，fina_indicator 字段修正为 or_yoy/dt_eps_yoy）。
--   4. 7 张整块删插表（top_list/sw_industry_member/pledge_detail/repurchase/
--      top10_holders/top10_floatholders/report_rc）不建唯一约束，按删除键先删后插。
--   5. 列类型沿用「业务语义类型」（VARCHAR/CHAR/DECIMAL/DATETIME 等），SQLite 按类型亲和性
--      存储：日期类 CHAR(8) 存 YYYYMMDD 文本、DATETIME 存 'yyyy-MM-dd HH:mm:ss' 文本、
--      DECIMAL 存数值、JSON 语义列用 TEXT（可用 SQLite JSON1 函数查询）。
--   6. 时间戳默认值用 datetime('now','localtime')（本地时间），与 Java LocalDateTime 写入口径一致；
--      MySQL 的 ON UPDATE CURRENT_TIMESTAMP 在 SQLite 不存在，更新时间由应用显式写入。
--   7. 单位口径见《Tushare接口对接手册》第 7 章：周/月线与 index_daily 的 vol=手、
--      amount=千元为 2026-11-23 实测结论（不换算）。
--   8. 生成列 judgment.active_flag 用 CASE WHEN（SQLite 无 IF 函数），唯一索引 uk_judgment_active 保证
--      同 (ts_code,trade_date,pipeline_id) 仅一行 superseded=0（置 1 后为 NULL 不参与唯一约束）。
-- ============================================================

-- ============================================================
-- 第一部分：Tushare 数据层（41 张，顺序 = 契约 §4.2）
-- ============================================================

-- stock_basic（api=stock_basic，维表）
CREATE TABLE IF NOT EXISTS stock_basic (
  -- [原 MySQL 表注释] 股票基础信息（幂等键 ts_code）
  ts_code VARCHAR(12) NOT NULL,
  symbol VARCHAR(16) NULL,
  name VARCHAR(128) NULL,
  area VARCHAR(32) NULL,
  industry VARCHAR(64) NULL,
  fullname VARCHAR(255) NULL,
  enname VARCHAR(255) NULL,
  cnspell VARCHAR(32) NULL,
  market VARCHAR(16) NULL,
  exchange VARCHAR(8) NULL,
  curr_type VARCHAR(8) NULL,
  list_status VARCHAR(8) NULL,
  list_date CHAR(8) NULL,
  delist_date CHAR(8) NULL,
  is_hs VARCHAR(8) NULL,
  act_name VARCHAR(128) NULL,
  act_ent_type VARCHAR(32) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_basic ON stock_basic (ts_code);


-- trade_cal（api=trade_cal，维表）
CREATE TABLE IF NOT EXISTS trade_cal (
  -- [原 MySQL 表注释] 交易日历（幂等键 exchange+cal_date）
  exchange VARCHAR(8) NOT NULL,
  cal_date CHAR(8) NOT NULL,
  is_open VARCHAR(8) NULL,
  pretrade_date CHAR(8) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_trade_cal ON trade_cal (exchange, cal_date);


-- stock_namechange（api=namechange，维表）
CREATE TABLE IF NOT EXISTS stock_namechange (
  -- [原 MySQL 表注释] 股票曾用名（幂等键 ts_code+start_date）
  ts_code VARCHAR(12) NOT NULL,
  name VARCHAR(128) NULL,
  start_date CHAR(8) NOT NULL,
  end_date CHAR(8) NULL,
  change_reason VARCHAR(512) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_namechange ON stock_namechange (ts_code, start_date);


-- daily（api=daily，行情）
CREATE TABLE IF NOT EXISTS daily (
  -- [原 MySQL 表注释] 日线行情（幂等键 ts_code+trade_date；vol=手 amount=千元 价格=元 pct_chg=%）
  ts_code VARCHAR(12) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  open DECIMAL(20,4) NULL,
  high DECIMAL(20,4) NULL,
  low DECIMAL(20,4) NULL,
  close DECIMAL(20,4) NULL,
  pre_close DECIMAL(20,4) NULL,
  change DECIMAL(20,4) NULL,
  pct_chg DECIMAL(20,4) NULL,
  vol DECIMAL(24,4) NULL,
  amount DECIMAL(24,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_daily ON daily (ts_code, trade_date);


-- adj_factor（api=adj_factor，行情）
CREATE TABLE IF NOT EXISTS adj_factor (
  -- [原 MySQL 表注释] 复权因子（幂等键 ts_code+trade_date）
  ts_code VARCHAR(12) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  adj_factor DECIMAL(20,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_adj_factor ON adj_factor (ts_code, trade_date);


-- daily_basic（api=daily_basic，行情）
CREATE TABLE IF NOT EXISTS daily_basic (
  -- [原 MySQL 表注释] 每日指标（幂等键 trade_date+ts_code；total_mv/circ_mv=万元 total_share/float_share/free_share=万股 换手率/股息率=%）
  ts_code VARCHAR(12) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  close DECIMAL(20,4) NULL,
  turnover_rate DECIMAL(20,4) NULL,
  turnover_rate_f DECIMAL(20,4) NULL,
  volume_ratio DECIMAL(20,4) NULL,
  pe DECIMAL(20,4) NULL,
  pe_ttm DECIMAL(20,4) NULL,
  pb DECIMAL(20,4) NULL,
  ps DECIMAL(20,4) NULL,
  ps_ttm DECIMAL(20,4) NULL,
  dv_ratio DECIMAL(20,4) NULL,
  dv_ttm DECIMAL(20,4) NULL,
  total_share DECIMAL(24,4) NULL,
  float_share DECIMAL(24,4) NULL,
  free_share DECIMAL(24,4) NULL,
  total_mv DECIMAL(24,4) NULL,
  circ_mv DECIMAL(24,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_daily_basic ON daily_basic (trade_date, ts_code);
-- 按标的回查历史窗口（估值分位单元每日读近 3 年序列）：唯一键是 (trade_date, ts_code)，
-- 前缀无法支撑「某标的的全部交易日」，缺此索引会退化为全表扫
CREATE INDEX IF NOT EXISTS idx_daily_basic_ts_code ON daily_basic (ts_code, trade_date);


-- stock_moneyflow（api=moneyflow，行情）
CREATE TABLE IF NOT EXISTS stock_moneyflow (
  -- [原 MySQL 表注释] 个股资金流向（幂等键 ts_code+trade_date；金额=万元 量=万手）
  ts_code VARCHAR(12) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  buy_sm_amount DECIMAL(24,4) NULL,
  sell_sm_amount DECIMAL(24,4) NULL,
  buy_sm_vol DECIMAL(24,4) NULL,
  sell_sm_vol DECIMAL(24,4) NULL,
  buy_md_amount DECIMAL(24,4) NULL,
  sell_md_amount DECIMAL(24,4) NULL,
  buy_md_vol DECIMAL(24,4) NULL,
  sell_md_vol DECIMAL(24,4) NULL,
  buy_lg_amount DECIMAL(24,4) NULL,
  sell_lg_amount DECIMAL(24,4) NULL,
  buy_lg_vol DECIMAL(24,4) NULL,
  sell_lg_vol DECIMAL(24,4) NULL,
  buy_elg_amount DECIMAL(24,4) NULL,
  sell_elg_amount DECIMAL(24,4) NULL,
  buy_elg_vol DECIMAL(24,4) NULL,
  sell_elg_vol DECIMAL(24,4) NULL,
  net_mf_amount DECIMAL(24,4) NULL,
  net_mf_vol DECIMAL(24,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_moneyflow ON stock_moneyflow (ts_code, trade_date);


-- stock_stk_limit（api=stk_limit，行情）
CREATE TABLE IF NOT EXISTS stock_stk_limit (
  -- [原 MySQL 表注释] 每日涨跌停价格（幂等键 ts_code+trade_date；价格=元）
  ts_code VARCHAR(12) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  pre_close DECIMAL(20,4) NULL,
  up_limit DECIMAL(20,4) NULL,
  down_limit DECIMAL(20,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_stk_limit ON stock_stk_limit (ts_code, trade_date);


-- stock_weekly_quote（api=stk_weekly_monthly，行情）
CREATE TABLE IF NOT EXISTS stock_weekly_quote (
  -- [原 MySQL 表注释] 周线行情（幂等键 ts_code+trade_date；vol=手 amount=千元 价格=元（实测不换算））
  ts_code VARCHAR(12) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  end_date CHAR(8) NULL,
  freq VARCHAR(8) NULL,
  open DECIMAL(20,4) NULL,
  high DECIMAL(20,4) NULL,
  low DECIMAL(20,4) NULL,
  close DECIMAL(20,4) NULL,
  pre_close DECIMAL(20,4) NULL,
  vol DECIMAL(24,4) NULL,
  amount DECIMAL(24,4) NULL,
  change DECIMAL(20,4) NULL,
  pct_chg DECIMAL(20,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_weekly_quote ON stock_weekly_quote (ts_code, trade_date);


-- stock_monthly_quote（api=stk_weekly_monthly，行情）
CREATE TABLE IF NOT EXISTS stock_monthly_quote (
  -- [原 MySQL 表注释] 月线行情（幂等键 ts_code+trade_date；vol=手 amount=千元 价格=元（实测不换算））
  ts_code VARCHAR(12) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  end_date CHAR(8) NULL,
  freq VARCHAR(8) NULL,
  open DECIMAL(20,4) NULL,
  high DECIMAL(20,4) NULL,
  low DECIMAL(20,4) NULL,
  close DECIMAL(20,4) NULL,
  pre_close DECIMAL(20,4) NULL,
  vol DECIMAL(24,4) NULL,
  amount DECIMAL(24,4) NULL,
  change DECIMAL(20,4) NULL,
  pct_chg DECIMAL(20,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_monthly_quote ON stock_monthly_quote (ts_code, trade_date);


-- dividend（api=dividend，财务）
CREATE TABLE IF NOT EXISTS dividend (
  -- [原 MySQL 表注释] 分红送股（幂等键 ts_code+end_date+ann_date；cash_div=元/股 stk_div=股/股 base_share=万股）
  ts_code VARCHAR(12) NOT NULL,
  end_date CHAR(8) NOT NULL,
  ann_date CHAR(8) NOT NULL,
  div_proc VARCHAR(32) NULL,
  stk_div DECIMAL(24,4) NULL,
  stk_bo_rate DECIMAL(20,4) NULL,
  stk_co_rate DECIMAL(20,4) NULL,
  cash_div DECIMAL(24,4) NULL,
  cash_div_tax DECIMAL(24,4) NULL,
  record_date CHAR(8) NULL,
  ex_date CHAR(8) NULL,
  pay_date CHAR(8) NULL,
  div_listdate CHAR(8) NULL,
  imp_ann_date CHAR(8) NULL,
  base_date CHAR(8) NULL,
  base_share DECIMAL(24,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dividend ON dividend (ts_code, end_date, ann_date);


-- fina_indicator（api=fina_indicator，财务）
CREATE TABLE IF NOT EXISTS fina_indicator (
  -- [原 MySQL 表注释] 财务指标（幂等键 ts_code+end_date；比率为 %）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NULL,
  end_date CHAR(8) NOT NULL,
  roe DECIMAL(20,4) NULL,
  roa DECIMAL(20,4) NULL,
  grossprofit_margin DECIMAL(20,4) NULL,
  netprofit_margin DECIMAL(20,4) NULL,
  dt_netprofit_yoy DECIMAL(20,4) NULL,
  or_yoy DECIMAL(20,4) NULL,
  debt_to_assets DECIMAL(20,4) NULL,
  dt_eps_yoy DECIMAL(20,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_fina_indicator ON fina_indicator (ts_code, end_date);


-- income（api=income，财务）
CREATE TABLE IF NOT EXISTS income (
  -- [原 MySQL 表注释] 利润表（幂等键 ts_code+end_date+report_type；金额=元 EPS=元/股）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NULL,
  f_ann_date CHAR(8) NULL,
  end_date CHAR(8) NOT NULL,
  report_type VARCHAR(16) NOT NULL,
  comp_type VARCHAR(8) NULL,
  end_type VARCHAR(8) NULL,
  basic_eps DECIMAL(20,4) NULL,
  diluted_eps DECIMAL(20,4) NULL,
  total_revenue DECIMAL(24,4) NULL,
  revenue DECIMAL(24,4) NULL,
  int_income DECIMAL(24,4) NULL,
  prem_earned DECIMAL(24,4) NULL,
  comm_income DECIMAL(24,4) NULL,
  n_commis_income DECIMAL(24,4) NULL,
  n_oth_income DECIMAL(24,4) NULL,
  n_oth_b_income DECIMAL(24,4) NULL,
  prem_income DECIMAL(24,4) NULL,
  out_prem DECIMAL(24,4) NULL,
  une_prem_reser DECIMAL(24,4) NULL,
  reins_income DECIMAL(24,4) NULL,
  n_sec_tb_income DECIMAL(24,4) NULL,
  n_sec_uw_income DECIMAL(24,4) NULL,
  n_asset_mg_income DECIMAL(24,4) NULL,
  oth_b_income DECIMAL(24,4) NULL,
  fv_value_chg_gain DECIMAL(24,4) NULL,
  invest_income DECIMAL(24,4) NULL,
  ass_invest_income DECIMAL(24,4) NULL,
  forex_gain DECIMAL(24,4) NULL,
  total_cogs DECIMAL(24,4) NULL,
  oper_cost DECIMAL(24,4) NULL,
  int_exp DECIMAL(24,4) NULL,
  comm_exp DECIMAL(24,4) NULL,
  biz_tax_surchg DECIMAL(24,4) NULL,
  sell_exp DECIMAL(24,4) NULL,
  admin_exp DECIMAL(24,4) NULL,
  fin_exp DECIMAL(24,4) NULL,
  assets_impair_loss DECIMAL(24,4) NULL,
  prem_refund DECIMAL(24,4) NULL,
  compens_payout DECIMAL(24,4) NULL,
  reser_insur_liab DECIMAL(24,4) NULL,
  div_payt DECIMAL(24,4) NULL,
  reins_exp DECIMAL(24,4) NULL,
  oper_exp DECIMAL(24,4) NULL,
  compens_payout_refu DECIMAL(24,4) NULL,
  insur_reser_refu DECIMAL(24,4) NULL,
  reins_cost_refund DECIMAL(24,4) NULL,
  other_bus_cost DECIMAL(24,4) NULL,
  operate_profit DECIMAL(24,4) NULL,
  non_oper_income DECIMAL(24,4) NULL,
  non_oper_exp DECIMAL(24,4) NULL,
  nca_disploss DECIMAL(24,4) NULL,
  total_profit DECIMAL(24,4) NULL,
  income_tax DECIMAL(24,4) NULL,
  n_income DECIMAL(24,4) NULL,
  n_income_attr_p DECIMAL(24,4) NULL,
  minority_gain DECIMAL(24,4) NULL,
  oth_compr_income DECIMAL(24,4) NULL,
  t_compr_income DECIMAL(24,4) NULL,
  compr_inc_attr_p DECIMAL(20,4) NULL,
  compr_inc_attr_m_s DECIMAL(20,4) NULL,
  ebit DECIMAL(20,4) NULL,
  ebitda DECIMAL(20,4) NULL,
  insurance_exp DECIMAL(24,4) NULL,
  undist_profit DECIMAL(24,4) NULL,
  distable_profit DECIMAL(24,4) NULL,
  rd_exp DECIMAL(24,4) NULL,
  fin_exp_int_exp DECIMAL(24,4) NULL,
  fin_exp_int_inc DECIMAL(24,4) NULL,
  transfer_surplus_rese DECIMAL(24,4) NULL,
  transfer_housing_imprest DECIMAL(24,4) NULL,
  transfer_oth DECIMAL(24,4) NULL,
  adj_lossgain DECIMAL(24,4) NULL,
  withdra_legal_surplus DECIMAL(24,4) NULL,
  withdra_legal_pubfund DECIMAL(24,4) NULL,
  withdra_biz_devfund DECIMAL(24,4) NULL,
  withdra_rese_fund DECIMAL(24,4) NULL,
  withdra_oth_ersu DECIMAL(24,4) NULL,
  workers_welfare DECIMAL(20,4) NULL,
  distr_profit_shrhder DECIMAL(24,4) NULL,
  prfshare_payable_dvd DECIMAL(24,4) NULL,
  comshare_payable_dvd DECIMAL(24,4) NULL,
  capit_comstock_div DECIMAL(24,4) NULL,
  net_after_nr_lp_correct DECIMAL(24,4) NULL,
  credit_impa_loss DECIMAL(24,4) NULL,
  net_expo_hedging_benefits DECIMAL(24,4) NULL,
  oth_impair_loss_assets DECIMAL(24,4) NULL,
  total_opcost DECIMAL(24,4) NULL,
  amodcost_fin_assets DECIMAL(24,4) NULL,
  oth_income DECIMAL(24,4) NULL,
  asset_disp_income DECIMAL(24,4) NULL,
  continued_net_profit DECIMAL(24,4) NULL,
  end_net_profit DECIMAL(24,4) NULL,
  update_flag VARCHAR(8) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_income ON income (ts_code, end_date, report_type);


-- balancesheet（api=balancesheet，财务）
CREATE TABLE IF NOT EXISTS balancesheet (
  -- [原 MySQL 表注释] 资产负债表（幂等键 ts_code+end_date+report_type；金额=元）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NULL,
  f_ann_date CHAR(8) NULL,
  end_date CHAR(8) NOT NULL,
  report_type VARCHAR(16) NOT NULL,
  comp_type VARCHAR(8) NULL,
  end_type VARCHAR(8) NULL,
  total_share DECIMAL(24,4) NULL,
  cap_rese DECIMAL(24,4) NULL,
  undistr_porfit DECIMAL(24,4) NULL,
  surplus_rese DECIMAL(20,4) NULL,
  special_rese DECIMAL(20,4) NULL,
  money_cap DECIMAL(24,4) NULL,
  trad_asset DECIMAL(20,4) NULL,
  notes_receiv DECIMAL(24,4) NULL,
  accounts_receiv DECIMAL(24,4) NULL,
  oth_receiv DECIMAL(24,4) NULL,
  prepayment DECIMAL(24,4) NULL,
  div_receiv DECIMAL(24,4) NULL,
  int_receiv DECIMAL(24,4) NULL,
  inventories DECIMAL(20,4) NULL,
  amor_exp DECIMAL(24,4) NULL,
  nca_within_1y DECIMAL(20,4) NULL,
  sett_rsrv DECIMAL(24,4) NULL,
  loanto_oth_bank_fi DECIMAL(24,4) NULL,
  premium_receiv DECIMAL(24,4) NULL,
  reinsur_receiv DECIMAL(24,4) NULL,
  reinsur_res_receiv DECIMAL(24,4) NULL,
  pur_resale_fa DECIMAL(24,4) NULL,
  oth_cur_assets DECIMAL(24,4) NULL,
  total_cur_assets DECIMAL(24,4) NULL,
  fa_avail_for_sale DECIMAL(24,4) NULL,
  htm_invest DECIMAL(24,4) NULL,
  lt_eqt_invest DECIMAL(24,4) NULL,
  invest_real_estate DECIMAL(24,4) NULL,
  time_deposits DECIMAL(24,4) NULL,
  oth_assets DECIMAL(24,4) NULL,
  lt_rec DECIMAL(24,4) NULL,
  fix_assets DECIMAL(24,4) NULL,
  cip DECIMAL(24,4) NULL,
  const_materials DECIMAL(24,4) NULL,
  fixed_assets_disp DECIMAL(24,4) NULL,
  produc_bio_assets DECIMAL(24,4) NULL,
  oil_and_gas_assets DECIMAL(24,4) NULL,
  intan_assets DECIMAL(24,4) NULL,
  r_and_d DECIMAL(24,4) NULL,
  goodwill DECIMAL(24,4) NULL,
  lt_amor_exp DECIMAL(24,4) NULL,
  defer_tax_assets DECIMAL(24,4) NULL,
  decr_in_disbur DECIMAL(24,4) NULL,
  oth_nca DECIMAL(24,4) NULL,
  total_nca DECIMAL(24,4) NULL,
  cash_reser_cb DECIMAL(24,4) NULL,
  depos_in_oth_bfi DECIMAL(24,4) NULL,
  prec_metals DECIMAL(20,4) NULL,
  deriv_assets DECIMAL(24,4) NULL,
  rr_reins_une_prem DECIMAL(24,4) NULL,
  rr_reins_outstd_cla DECIMAL(24,4) NULL,
  rr_reins_lins_liab DECIMAL(24,4) NULL,
  rr_reins_lthins_liab DECIMAL(24,4) NULL,
  refund_depos DECIMAL(24,4) NULL,
  ph_pledge_loans DECIMAL(24,4) NULL,
  refund_cap_depos DECIMAL(24,4) NULL,
  indep_acct_assets DECIMAL(24,4) NULL,
  client_depos DECIMAL(24,4) NULL,
  client_prov DECIMAL(24,4) NULL,
  transac_seat_fee DECIMAL(24,4) NULL,
  invest_as_receiv DECIMAL(24,4) NULL,
  total_assets DECIMAL(24,4) NULL,
  lt_borr DECIMAL(24,4) NULL,
  st_borr DECIMAL(24,4) NULL,
  cb_borr DECIMAL(24,4) NULL,
  depos_ib_deposits DECIMAL(24,4) NULL,
  loan_oth_bank DECIMAL(24,4) NULL,
  trading_fl DECIMAL(20,4) NULL,
  notes_payable DECIMAL(24,4) NULL,
  acct_payable DECIMAL(24,4) NULL,
  adv_receipts DECIMAL(20,4) NULL,
  sold_for_repur_fa DECIMAL(24,4) NULL,
  comm_payable DECIMAL(24,4) NULL,
  payroll_payable DECIMAL(24,4) NULL,
  taxes_payable DECIMAL(24,4) NULL,
  int_payable DECIMAL(24,4) NULL,
  div_payable DECIMAL(24,4) NULL,
  oth_payable DECIMAL(24,4) NULL,
  acc_exp DECIMAL(24,4) NULL,
  deferred_inc DECIMAL(24,4) NULL,
  st_bonds_payable DECIMAL(24,4) NULL,
  payable_to_reinsurer DECIMAL(24,4) NULL,
  rsrv_insur_cont DECIMAL(20,4) NULL,
  acting_trading_sec DECIMAL(20,4) NULL,
  acting_uw_sec DECIMAL(20,4) NULL,
  non_cur_liab_due_1y DECIMAL(24,4) NULL,
  oth_cur_liab DECIMAL(24,4) NULL,
  total_cur_liab DECIMAL(24,4) NULL,
  bond_payable DECIMAL(24,4) NULL,
  lt_payable DECIMAL(24,4) NULL,
  specific_payables DECIMAL(24,4) NULL,
  estimated_liab DECIMAL(24,4) NULL,
  defer_tax_liab DECIMAL(24,4) NULL,
  defer_inc_non_cur_liab DECIMAL(24,4) NULL,
  oth_ncl DECIMAL(24,4) NULL,
  total_ncl DECIMAL(24,4) NULL,
  depos_oth_bfi DECIMAL(24,4) NULL,
  deriv_liab DECIMAL(24,4) NULL,
  depos DECIMAL(24,4) NULL,
  agency_bus_liab DECIMAL(24,4) NULL,
  oth_liab DECIMAL(24,4) NULL,
  prem_receiv_adva DECIMAL(24,4) NULL,
  depos_received DECIMAL(24,4) NULL,
  ph_invest DECIMAL(24,4) NULL,
  reser_une_prem DECIMAL(24,4) NULL,
  reser_outstd_claims DECIMAL(24,4) NULL,
  reser_lins_liab DECIMAL(24,4) NULL,
  reser_lthins_liab DECIMAL(24,4) NULL,
  indept_acc_liab DECIMAL(24,4) NULL,
  pledge_borr DECIMAL(24,4) NULL,
  indem_payable DECIMAL(24,4) NULL,
  policy_div_payable DECIMAL(24,4) NULL,
  total_liab DECIMAL(24,4) NULL,
  treasury_share DECIMAL(24,4) NULL,
  ordin_risk_reser DECIMAL(24,4) NULL,
  forex_differ DECIMAL(24,4) NULL,
  invest_loss_unconf DECIMAL(24,4) NULL,
  minority_int DECIMAL(24,4) NULL,
  total_hldr_eqy_exc_min_int DECIMAL(24,4) NULL,
  total_hldr_eqy_inc_min_int DECIMAL(24,4) NULL,
  total_liab_hldr_eqy DECIMAL(24,4) NULL,
  lt_payroll_payable DECIMAL(24,4) NULL,
  oth_comp_income DECIMAL(24,4) NULL,
  oth_eqt_tools DECIMAL(24,4) NULL,
  oth_eqt_tools_p_shr DECIMAL(24,4) NULL,
  lending_funds DECIMAL(24,4) NULL,
  acc_receivable DECIMAL(24,4) NULL,
  st_fin_payable DECIMAL(24,4) NULL,
  payables DECIMAL(24,4) NULL,
  hfs_assets DECIMAL(24,4) NULL,
  hfs_sales DECIMAL(24,4) NULL,
  cost_fin_assets DECIMAL(24,4) NULL,
  fair_value_fin_assets DECIMAL(24,4) NULL,
  cip_total DECIMAL(24,4) NULL,
  oth_pay_total DECIMAL(24,4) NULL,
  long_pay_total DECIMAL(24,4) NULL,
  debt_invest DECIMAL(24,4) NULL,
  oth_debt_invest DECIMAL(24,4) NULL,
  oth_eq_invest DECIMAL(24,4) NULL,
  oth_illiq_fin_assets DECIMAL(24,4) NULL,
  oth_eq_ppbond DECIMAL(24,4) NULL,
  receiv_financing DECIMAL(24,4) NULL,
  use_right_assets DECIMAL(24,4) NULL,
  lease_liab DECIMAL(24,4) NULL,
  contract_assets DECIMAL(24,4) NULL,
  contract_liab DECIMAL(24,4) NULL,
  accounts_receiv_bill DECIMAL(24,4) NULL,
  accounts_pay DECIMAL(24,4) NULL,
  oth_rcv_total DECIMAL(24,4) NULL,
  fix_assets_total DECIMAL(24,4) NULL,
  update_flag VARCHAR(8) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_balancesheet ON balancesheet (ts_code, end_date, report_type);


-- cashflow（api=cashflow，财务）
CREATE TABLE IF NOT EXISTS cashflow (
  -- [原 MySQL 表注释] 现金流量表（幂等键 ts_code+end_date+report_type；金额=元）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NULL,
  f_ann_date CHAR(8) NULL,
  end_date CHAR(8) NOT NULL,
  comp_type VARCHAR(8) NULL,
  report_type VARCHAR(16) NOT NULL,
  end_type VARCHAR(8) NULL,
  net_profit DECIMAL(24,4) NULL,
  finan_exp DECIMAL(24,4) NULL,
  c_fr_sale_sg DECIMAL(24,4) NULL,
  recp_tax_rends DECIMAL(24,4) NULL,
  n_depos_incr_fi DECIMAL(24,4) NULL,
  n_incr_loans_cb DECIMAL(24,4) NULL,
  n_inc_borr_oth_fi DECIMAL(24,4) NULL,
  prem_fr_orig_contr DECIMAL(24,4) NULL,
  n_incr_insured_dep DECIMAL(24,4) NULL,
  n_reinsur_prem DECIMAL(24,4) NULL,
  n_incr_disp_tfa DECIMAL(24,4) NULL,
  ifc_cash_incr DECIMAL(24,4) NULL,
  n_incr_disp_faas DECIMAL(24,4) NULL,
  n_incr_loans_oth_bank DECIMAL(24,4) NULL,
  n_cap_incr_repur DECIMAL(24,4) NULL,
  c_fr_oth_operate_a DECIMAL(24,4) NULL,
  c_inf_fr_operate_a DECIMAL(24,4) NULL,
  c_paid_goods_s DECIMAL(20,4) NULL,
  c_paid_to_for_empl DECIMAL(20,4) NULL,
  c_paid_for_taxes DECIMAL(24,4) NULL,
  n_incr_clt_loan_adv DECIMAL(24,4) NULL,
  n_incr_dep_cbob DECIMAL(24,4) NULL,
  c_pay_claims_orig_inco DECIMAL(24,4) NULL,
  pay_handling_chrg DECIMAL(24,4) NULL,
  pay_comm_insur_plcy DECIMAL(24,4) NULL,
  oth_cash_pay_oper_act DECIMAL(24,4) NULL,
  st_cash_out_act DECIMAL(24,4) NULL,
  n_cashflow_act DECIMAL(24,4) NULL,
  oth_recp_ral_inv_act DECIMAL(24,4) NULL,
  c_disp_withdrwl_invest DECIMAL(24,4) NULL,
  c_recp_return_invest DECIMAL(24,4) NULL,
  n_recp_disp_fiolta DECIMAL(24,4) NULL,
  n_recp_disp_sobu DECIMAL(24,4) NULL,
  stot_inflows_inv_act DECIMAL(20,4) NULL,
  c_pay_acq_const_fiolta DECIMAL(24,4) NULL,
  c_paid_invest DECIMAL(24,4) NULL,
  n_disp_subs_oth_biz DECIMAL(24,4) NULL,
  oth_pay_ral_inv_act DECIMAL(24,4) NULL,
  n_incr_pledge_loan DECIMAL(24,4) NULL,
  stot_out_inv_act DECIMAL(20,4) NULL,
  n_cashflow_inv_act DECIMAL(24,4) NULL,
  c_recp_borrow DECIMAL(24,4) NULL,
  proc_issue_bonds DECIMAL(20,4) NULL,
  oth_cash_recp_ral_fnc_act DECIMAL(24,4) NULL,
  stot_cash_in_fnc_act DECIMAL(24,4) NULL,
  free_cashflow DECIMAL(24,4) NULL,
  c_prepay_amt_borr DECIMAL(24,4) NULL,
  c_pay_dist_dpcp_int_exp DECIMAL(24,4) NULL,
  incl_dvd_profit_paid_sc_ms DECIMAL(24,4) NULL,
  oth_cashpay_ral_fnc_act DECIMAL(24,4) NULL,
  stot_cashout_fnc_act DECIMAL(24,4) NULL,
  n_cash_flows_fnc_act DECIMAL(24,4) NULL,
  eff_fx_flu_cash DECIMAL(24,4) NULL,
  n_incr_cash_cash_equ DECIMAL(24,4) NULL,
  c_cash_equ_beg_period DECIMAL(24,4) NULL,
  c_cash_equ_end_period DECIMAL(24,4) NULL,
  c_recp_cap_contrib DECIMAL(24,4) NULL,
  incl_cash_rec_saims DECIMAL(24,4) NULL,
  uncon_invest_loss DECIMAL(24,4) NULL,
  prov_depr_assets DECIMAL(24,4) NULL,
  depr_fa_coga_dpba DECIMAL(24,4) NULL,
  amort_intang_assets DECIMAL(24,4) NULL,
  lt_amort_deferred_exp DECIMAL(24,4) NULL,
  decr_deferred_exp DECIMAL(24,4) NULL,
  incr_acc_exp DECIMAL(24,4) NULL,
  loss_disp_fiolta DECIMAL(24,4) NULL,
  loss_scr_fa DECIMAL(24,4) NULL,
  loss_fv_chg DECIMAL(24,4) NULL,
  invest_loss DECIMAL(24,4) NULL,
  decr_def_inc_tax_assets DECIMAL(24,4) NULL,
  incr_def_inc_tax_liab DECIMAL(24,4) NULL,
  decr_inventories DECIMAL(24,4) NULL,
  decr_oper_payable DECIMAL(24,4) NULL,
  incr_oper_payable DECIMAL(24,4) NULL,
  others DECIMAL(24,4) NULL,
  im_net_cashflow_oper_act DECIMAL(24,4) NULL,
  conv_debt_into_cap DECIMAL(24,4) NULL,
  conv_copbonds_due_within_1y DECIMAL(24,4) NULL,
  fa_fnc_leases DECIMAL(24,4) NULL,
  im_n_incr_cash_equ DECIMAL(24,4) NULL,
  net_dism_capital_add DECIMAL(24,4) NULL,
  net_cash_rece_sec DECIMAL(24,4) NULL,
  credit_impa_loss DECIMAL(24,4) NULL,
  use_right_asset_dep DECIMAL(24,4) NULL,
  oth_loss_asset DECIMAL(24,4) NULL,
  end_bal_cash DECIMAL(24,4) NULL,
  beg_bal_cash DECIMAL(24,4) NULL,
  end_bal_cash_equ DECIMAL(24,4) NULL,
  beg_bal_cash_equ DECIMAL(24,4) NULL,
  update_flag VARCHAR(8) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cashflow ON cashflow (ts_code, end_date, report_type);


-- forecast（api=forecast，财务）
CREATE TABLE IF NOT EXISTS forecast (
  -- [原 MySQL 表注释] 业绩预告（幂等键 ts_code+end_date+ann_date；p_change=%, net_profit=万元）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NOT NULL,
  end_date CHAR(8) NOT NULL,
  type VARCHAR(64) NULL,
  p_change_min DECIMAL(20,4) NULL,
  p_change_max DECIMAL(20,4) NULL,
  net_profit_min DECIMAL(24,4) NULL,
  net_profit_max DECIMAL(24,4) NULL,
  last_parent_net DECIMAL(24,4) NULL,
  summary VARCHAR(512) NULL,
  change_reason VARCHAR(512) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_forecast ON forecast (ts_code, end_date, ann_date);


-- express（api=express，财务）
CREATE TABLE IF NOT EXISTS express (
  -- [原 MySQL 表注释] 业绩快报（幂等键 ts_code+end_date；金额=元 比率=%）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NULL,
  end_date CHAR(8) NOT NULL,
  revenue DECIMAL(24,4) NULL,
  operate_profit DECIMAL(24,4) NULL,
  total_profit DECIMAL(24,4) NULL,
  n_income DECIMAL(24,4) NULL,
  total_assets DECIMAL(24,4) NULL,
  total_hldr_eqy_exc_min_int DECIMAL(24,4) NULL,
  diluted_eps DECIMAL(20,4) NULL,
  diluted_roe DECIMAL(20,4) NULL,
  yoy_net_profit DECIMAL(20,4) NULL,
  bps DECIMAL(20,4) NULL,
  perf_summary VARCHAR(512) NULL,
  update_flag VARCHAR(8) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_express ON express (ts_code, end_date);


-- fina_audit（api=fina_audit，财务）
CREATE TABLE IF NOT EXISTS fina_audit (
  -- [原 MySQL 表注释] 财务审计意见（幂等键 ts_code+end_date+ann_date；audit_fees=元）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NOT NULL,
  end_date CHAR(8) NOT NULL,
  audit_result VARCHAR(512) NULL,
  audit_fees DECIMAL(24,4) NULL,
  audit_agency VARCHAR(128) NULL,
  audit_sign VARCHAR(255) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_fina_audit ON fina_audit (ts_code, end_date, ann_date);


-- fina_mainbz（api=fina_mainbz，财务）
CREATE TABLE IF NOT EXISTS fina_mainbz (
  -- [原 MySQL 表注释] 主营业务构成（幂等键 ts_code+end_date+bz_item+bz_code；金额=元）
  ts_code VARCHAR(12) NOT NULL,
  end_date CHAR(8) NOT NULL,
  bz_item VARCHAR(255) NOT NULL,
  bz_code VARCHAR(64) NOT NULL,
  bz_sales DECIMAL(24,4) NULL,
  bz_profit DECIMAL(24,4) NULL,
  bz_cost DECIMAL(24,4) NULL,
  curr_type VARCHAR(8) NULL,
  update_flag VARCHAR(8) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_fina_mainbz ON fina_mainbz (ts_code, end_date, bz_item, bz_code);


-- disclosure_date（api=disclosure_date，财务）
CREATE TABLE IF NOT EXISTS disclosure_date (
  -- [原 MySQL 表注释] 财报披露计划（幂等键 ts_code+end_date）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NULL,
  end_date CHAR(8) NOT NULL,
  pre_date CHAR(8) NULL,
  actual_date CHAR(8) NULL,
  modify_date VARCHAR(255) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_disclosure_date ON disclosure_date (ts_code, end_date);


-- top_list（api=top_list，事件）
CREATE TABLE IF NOT EXISTS top_list (
  -- [原 MySQL 表注释] 龙虎榜每日明细（删除键 trade_date+ts_code+name+reason；close=元 amount=元 比率为%）
  trade_date CHAR(8) NOT NULL,
  ts_code VARCHAR(12) NOT NULL,
  name VARCHAR(128) NOT NULL,
  close DECIMAL(20,4) NULL,
  pct_change DECIMAL(20,4) NULL,
  turnover_rate DECIMAL(20,4) NULL,
  amount DECIMAL(24,4) NULL,
  l_sell DECIMAL(20,4) NULL,
  l_buy DECIMAL(20,4) NULL,
  l_amount DECIMAL(24,4) NULL,
  net_amount DECIMAL(24,4) NULL,
  net_rate DECIMAL(20,4) NULL,
  amount_rate DECIMAL(20,4) NULL,
  float_values DECIMAL(24,4) NULL,
  reason VARCHAR(255) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_top_list_del ON top_list (trade_date, ts_code, name, reason);


-- top_inst（api=top_inst，事件）
CREATE TABLE IF NOT EXISTS top_inst (
  -- [原 MySQL 表注释] 龙虎榜机构明细（幂等键 trade_date+ts_code+exalter+side；buy/sell/net_buy=元 比率为%）
  trade_date CHAR(8) NOT NULL,
  ts_code VARCHAR(12) NOT NULL,
  exalter VARCHAR(128) NOT NULL,
  side VARCHAR(8) NOT NULL,
  buy DECIMAL(20,4) NULL,
  buy_rate DECIMAL(20,4) NULL,
  sell DECIMAL(20,4) NULL,
  sell_rate DECIMAL(20,4) NULL,
  net_buy DECIMAL(24,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_top_inst ON top_inst (trade_date, ts_code, exalter, side);


-- block_trade（api=block_trade，事件）
CREATE TABLE IF NOT EXISTS block_trade (
  -- [原 MySQL 表注释] 大宗交易（幂等键 trade_date+ts_code+buyer+seller；vol=万股 amount=万元 价格=元）
  ts_code VARCHAR(12) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  price DECIMAL(20,4) NULL,
  vol DECIMAL(24,4) NULL,
  amount DECIMAL(24,4) NULL,
  buyer VARCHAR(32) NOT NULL,
  seller VARCHAR(32) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_block_trade ON block_trade (trade_date, ts_code, buyer, seller);


-- stock_suspend_d（api=suspend_d，事件）
CREATE TABLE IF NOT EXISTS stock_suspend_d (
  -- [原 MySQL 表注释] 每日停复牌（幂等键 ts_code+trade_date+suspend_type）
  ts_code VARCHAR(12) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  suspend_timing VARCHAR(32) NULL,
  suspend_type VARCHAR(8) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_suspend_d ON stock_suspend_d (ts_code, trade_date, suspend_type);


-- share_float（api=share_float，事件）
CREATE TABLE IF NOT EXISTS share_float (
  -- [原 MySQL 表注释] 限售股解禁（幂等键 ts_code+float_date+holder_name；float_share=股 float_ratio=%）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NULL,
  float_date CHAR(8) NOT NULL,
  float_share DECIMAL(24,4) NULL,
  float_ratio DECIMAL(20,4) NULL,
  holder_name VARCHAR(128) NOT NULL,
  share_type VARCHAR(64) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_share_float ON share_float (ts_code, float_date, holder_name);


-- pledge_stat（api=pledge_stat，事件）
CREATE TABLE IF NOT EXISTS pledge_stat (
  -- [原 MySQL 表注释] 股权质押统计（幂等键 ts_code+end_date；质押数量=万股 比例=%）
  ts_code VARCHAR(12) NOT NULL,
  end_date CHAR(8) NOT NULL,
  pledge_count DECIMAL(20,4) NULL,
  unrest_pledge DECIMAL(24,4) NULL,
  rest_pledge DECIMAL(24,4) NULL,
  total_share DECIMAL(24,4) NULL,
  pledge_ratio DECIMAL(20,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pledge_stat ON pledge_stat (ts_code, end_date);


-- pledge_detail（api=pledge_detail，事件）
CREATE TABLE IF NOT EXISTS pledge_detail (
  -- [原 MySQL 表注释] 股权质押明细（删除键 ts_code+ann_date；质押数量=万股 比例=%）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NOT NULL,
  holder_name VARCHAR(128) NULL,
  pledge_amount DECIMAL(24,4) NULL,
  start_date CHAR(8) NULL,
  end_date CHAR(8) NULL,
  is_release VARCHAR(8) NULL,
  release_date CHAR(8) NULL,
  pledgor VARCHAR(128) NULL,
  holding_amount DECIMAL(24,4) NULL,
  pledged_amount DECIMAL(24,4) NULL,
  p_total_ratio DECIMAL(20,4) NULL,
  h_total_ratio DECIMAL(20,4) NULL,
  is_buyback VARCHAR(8) NULL
);
CREATE INDEX IF NOT EXISTS idx_pledge_detail_del ON pledge_detail (ts_code, ann_date);


-- repurchase（api=repurchase，事件）
CREATE TABLE IF NOT EXISTS repurchase (
  -- [原 MySQL 表注释] 股票回购（删除键 ts_code+ann_date；vol=万股 amount=万元 价格=元）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NOT NULL,
  end_date CHAR(8) NULL,
  proc VARCHAR(64) NULL,
  exp_date CHAR(8) NULL,
  vol DECIMAL(24,4) NULL,
  amount DECIMAL(24,4) NULL,
  high_limit DECIMAL(20,4) NULL,
  low_limit DECIMAL(20,4) NULL
);
CREATE INDEX IF NOT EXISTS idx_repurchase_del ON repurchase (ts_code, ann_date);


-- top10_holders（api=top10_holders，事件）
CREATE TABLE IF NOT EXISTS top10_holders (
  -- [原 MySQL 表注释] 前十大股东（删除键 ts_code+end_date；hold_amount=股 比例=%）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NULL,
  end_date CHAR(8) NOT NULL,
  holder_name VARCHAR(128) NULL,
  hold_amount DECIMAL(24,4) NULL,
  hold_ratio DECIMAL(20,4) NULL,
  hold_float_ratio DECIMAL(20,4) NULL,
  hold_change DECIMAL(24,4) NULL,
  holder_type VARCHAR(32) NULL
);
CREATE INDEX IF NOT EXISTS idx_top10_holders_del ON top10_holders (ts_code, end_date);


-- top10_floatholders（api=top10_floatholders，事件）
CREATE TABLE IF NOT EXISTS top10_floatholders (
  -- [原 MySQL 表注释] 前十大流通股东（删除键 ts_code+end_date；hold_amount=股 比例=%）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NULL,
  end_date CHAR(8) NOT NULL,
  holder_name VARCHAR(128) NULL,
  hold_amount DECIMAL(24,4) NULL,
  hold_ratio DECIMAL(20,4) NULL,
  hold_float_ratio DECIMAL(20,4) NULL,
  hold_change DECIMAL(24,4) NULL,
  holder_type VARCHAR(32) NULL
);
CREATE INDEX IF NOT EXISTS idx_top10_floatholders_del ON top10_floatholders (ts_code, end_date);


-- stk_holdertrade（api=stk_holdertrade，事件）
CREATE TABLE IF NOT EXISTS stk_holdertrade (
  -- [原 MySQL 表注释] 股东增减持（幂等键 ts_code+ann_date+holder_name+in_de+begin_date+close_date；change_vol=万股 比例=%）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NOT NULL,
  holder_name VARCHAR(128) NOT NULL,
  holder_type VARCHAR(32) NULL,
  in_de VARCHAR(8) NOT NULL,
  change_vol DECIMAL(24,4) NULL,
  change_ratio DECIMAL(20,4) NULL,
  after_share DECIMAL(24,4) NULL,
  after_ratio DECIMAL(20,4) NULL,
  avg_price DECIMAL(20,4) NULL,
  total_share DECIMAL(24,4) NULL,
  begin_date CHAR(8) NOT NULL,
  close_date CHAR(8) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stk_holdertrade ON stk_holdertrade (ts_code, ann_date, holder_name, in_de, begin_date, close_date);


-- stk_holdernumber（api=stk_holdernumber，事件）
CREATE TABLE IF NOT EXISTS stk_holdernumber (
  -- [原 MySQL 表注释] 股东户数（幂等键 ts_code+end_date；holder_num=户）
  ts_code VARCHAR(12) NOT NULL,
  ann_date CHAR(8) NULL,
  end_date CHAR(8) NOT NULL,
  holder_num DECIMAL(20,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stk_holdernumber ON stk_holdernumber (ts_code, end_date);


-- report_rc（api=report_rc，事件）
CREATE TABLE IF NOT EXISTS report_rc (
  -- [原 MySQL 表注释] 券商盈利预测（删除键 ts_code+report_date+org_name；op_rt/op_pr/tp/np=万元 比率为%）
  ts_code VARCHAR(12) NOT NULL,
  name VARCHAR(128) NULL,
  report_date CHAR(8) NOT NULL,
  report_title VARCHAR(512) NULL,
  report_type VARCHAR(16) NULL,
  classify VARCHAR(128) NULL,
  org_name VARCHAR(64) NOT NULL,
  author_name VARCHAR(64) NULL,
  quarter VARCHAR(16) NULL,
  op_rt DECIMAL(20,4) NULL,
  op_pr DECIMAL(20,4) NULL,
  tp DECIMAL(20,4) NULL,
  np DECIMAL(20,4) NULL,
  eps DECIMAL(20,4) NULL,
  pe DECIMAL(20,4) NULL,
  rd DECIMAL(20,4) NULL,
  roe DECIMAL(20,4) NULL,
  ev_ebitda DECIMAL(20,4) NULL,
  rating VARCHAR(32) NULL,
  max_price DECIMAL(20,4) NULL,
  min_price DECIMAL(20,4) NULL,
  imp_dg VARCHAR(512) NULL,
  create_time VARCHAR(20) NULL
);
CREATE INDEX IF NOT EXISTS idx_report_rc_del ON report_rc (ts_code, report_date, org_name);


-- index_basic（api=index_basic，维表）
CREATE TABLE IF NOT EXISTS index_basic (
  -- [原 MySQL 表注释] 指数基础信息（幂等键 ts_code）
  ts_code VARCHAR(12) NOT NULL,
  name VARCHAR(128) NULL,
  fullname VARCHAR(255) NULL,
  market VARCHAR(16) NULL,
  publisher VARCHAR(64) NULL,
  index_type VARCHAR(32) NULL,
  category VARCHAR(32) NULL,
  base_date CHAR(8) NULL,
  base_point DECIMAL(20,4) NULL,
  list_date CHAR(8) NULL,
  weight_rule VARCHAR(255) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_index_basic ON index_basic (ts_code);


-- index_daily（api=index_daily，行情）
CREATE TABLE IF NOT EXISTS index_daily (
  -- [原 MySQL 表注释] 指数日线行情（幂等键 ts_code+trade_date；vol=手 amount=千元 点位（实测与 daily 同口径））
  ts_code VARCHAR(12) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  close DECIMAL(20,4) NULL,
  open DECIMAL(20,4) NULL,
  high DECIMAL(20,4) NULL,
  low DECIMAL(20,4) NULL,
  pre_close DECIMAL(20,4) NULL,
  change DECIMAL(20,4) NULL,
  pct_chg DECIMAL(20,4) NULL,
  vol DECIMAL(24,4) NULL,
  amount DECIMAL(24,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_index_daily ON index_daily (ts_code, trade_date);


-- index_weight（api=index_weight，指数与市场）
CREATE TABLE IF NOT EXISTS index_weight (
  -- [原 MySQL 表注释] 指数成分权重（幂等键 index_code+trade_date+con_code；weight=%）
  index_code VARCHAR(12) NOT NULL,
  con_code VARCHAR(12) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  weight DECIMAL(20,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_index_weight ON index_weight (index_code, trade_date, con_code);


-- sw_industry（api=index_classify，维表）
CREATE TABLE IF NOT EXISTS sw_industry (
  -- [原 MySQL 表注释] 申万行业分类（幂等键 index_code+src）
  index_code VARCHAR(12) NOT NULL,
  industry_name VARCHAR(64) NULL,
  level VARCHAR(8) NULL,
  industry_code VARCHAR(12) NULL,
  is_pub VARCHAR(8) NULL,
  parent_code VARCHAR(12) NULL,
  src VARCHAR(16) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_industry ON sw_industry (index_code, src);


-- sw_industry_member（api=index_member_all，维表）
CREATE TABLE IF NOT EXISTS sw_industry_member (
  -- [原 MySQL 表注释] 申万行业成分（全量替换，无幂等键）
  l1_code VARCHAR(12) NULL,
  l1_name VARCHAR(64) NULL,
  l2_code VARCHAR(12) NULL,
  l2_name VARCHAR(64) NULL,
  l3_code VARCHAR(12) NULL,
  l3_name VARCHAR(64) NULL,
  ts_code VARCHAR(12) NULL,
  name VARCHAR(128) NULL,
  in_date CHAR(8) NULL,
  out_date CHAR(8) NULL,
  is_new VARCHAR(8) NULL
);
-- 行业成分按股票回查是高频路径（全表替换后按 ts_code 过滤），补普通索引
CREATE INDEX IF NOT EXISTS idx_sw_industry_member_ts_code ON sw_industry_member (ts_code);


-- hk_hold（api=hk_hold，行情）
CREATE TABLE IF NOT EXISTS hk_hold (
  -- [原 MySQL 表注释] 沪深港通持股（幂等键 trade_date+code；vol=万股 ratio=%）
  trade_date CHAR(8) NOT NULL,
  code VARCHAR(12) NOT NULL,
  name VARCHAR(128) NULL,
  vol DECIMAL(24,4) NULL,
  ratio DECIMAL(20,4) NULL,
  ts_code VARCHAR(12) NULL,
  exchange VARCHAR(8) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_hk_hold ON hk_hold (trade_date, code);


-- margin（api=margin，指数与市场）
CREATE TABLE IF NOT EXISTS margin (
  -- [原 MySQL 表注释] 融资融券汇总（幂等键 exchange_id+trade_date；金额=万元 rqmcl=万股）
  exchange_id VARCHAR(8) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  rzye DECIMAL(20,4) NULL,
  rzmre DECIMAL(20,4) NULL,
  rzche DECIMAL(20,4) NULL,
  rqye DECIMAL(20,4) NULL,
  rqmcl DECIMAL(20,4) NULL,
  rzrqye DECIMAL(20,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_margin ON margin (exchange_id, trade_date);


-- margin_detail（api=margin_detail，指数与市场）
CREATE TABLE IF NOT EXISTS margin_detail (
  -- [原 MySQL 表注释] 融资融券明细（幂等键 trade_date+ts_code；金额=万元 rqmcl=万股）
  trade_date CHAR(8) NOT NULL,
  ts_code VARCHAR(12) NOT NULL,
  name VARCHAR(128) NULL,
  rzye DECIMAL(20,4) NULL,
  rqye DECIMAL(20,4) NULL,
  rzmre DECIMAL(20,4) NULL,
  rzche DECIMAL(20,4) NULL,
  rqmcl DECIMAL(20,4) NULL,
  rzrqye DECIMAL(20,4) NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_margin_detail ON margin_detail (trade_date, ts_code);


-- ============================================================
-- 第二部分：业务表（20 张）
-- 说明：事实/判断/流水线/报告/版本/系统六层 + quality_check_log（原型 S0 历史需要）
--       + 账号域（sys_user/sys_user_session/user_preference/user_watchlist）
-- ============================================================

-- 事实层：能力单元事实（payload JSON）
CREATE TABLE IF NOT EXISTS unit_fact (
  -- [原 MySQL 表注释] 事实层-能力单元事实（幂等键 unit_id+unit_version+ts_code+trade_date）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  unit_id VARCHAR(40) NOT NULL,
  unit_version VARCHAR(20) NOT NULL,
  ts_code VARCHAR(12) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  payload TEXT NOT NULL,
  data_status VARCHAR(16) NOT NULL DEFAULT 'COMPLETE',
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_unit_fact ON unit_fact (unit_id, unit_version, ts_code, trade_date);
CREATE INDEX IF NOT EXISTS idx_unit_fact_date ON unit_fact (trade_date, ts_code);


-- 判断层：判断存档（active_flag 生成列 + uk_judgment_active 保证同组仅一行 superseded=0）
CREATE TABLE IF NOT EXISTS judgment (
  -- [原 MySQL 表注释] 判断层-判断存档（同组仅一行 superseded=0，由生成列 + uk_judgment_active 保证）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  judgment_id VARCHAR(40) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  ts_code VARCHAR(12) NOT NULL,
  pipeline_id VARCHAR(8) NOT NULL,
  report_id BIGINT NOT NULL,
  method_version VARCHAR(20) NOT NULL,
  direction VARCHAR(16) NOT NULL,
  horizon_days INT NOT NULL,
  expire_at DATE NOT NULL,
  confidence VARCHAR(4) NOT NULL,
  payload TEXT NOT NULL,
  frozen TINYINT NOT NULL DEFAULT 1,
  superseded TINYINT NOT NULL DEFAULT 0,
  active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN superseded = 0 THEN 1 ELSE NULL END) STORED,
  invalidated_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_judgment ON judgment (judgment_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_judgment_active ON judgment (ts_code, trade_date, pipeline_id, active_flag);
CREATE INDEX IF NOT EXISTS idx_judgment_active ON judgment (trade_date, ts_code, pipeline_id, superseded);
CREATE INDEX IF NOT EXISTS idx_judgment_expire ON judgment (expire_at, frozen);
CREATE INDEX IF NOT EXISTS idx_judgment_direction ON judgment (direction, method_version);


-- 判断层：判断引用的能力单元输出
CREATE TABLE IF NOT EXISTS judgment_unit_ref (
  -- [原 MySQL 表注释] 判断层-判断与能力单元引用（幂等键 judgment_id+unit_id）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  judgment_id VARCHAR(40) NOT NULL,
  unit_id VARCHAR(40) NOT NULL,
  unit_version VARCHAR(20) NOT NULL,
  output_summary TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_judgment_unit_ref ON judgment_unit_ref (judgment_id, unit_id);


-- 判断层：逻辑标签字典
CREATE TABLE IF NOT EXISTS logic_tag_dict (
  -- [原 MySQL 表注释] 判断层-逻辑标签字典（tag_code 主键）
  tag_code VARCHAR(40) NOT NULL,
  tag_name VARCHAR(64) NOT NULL,
  category VARCHAR(32) NOT NULL,
  description VARCHAR(512) NULL,
  active TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime')),
  PRIMARY KEY (tag_code)
);


-- 流水线层：一次编排
CREATE TABLE IF NOT EXISTS pipeline_run (
  -- [原 MySQL 表注释] 流水线层-编排运行（run_id 唯一）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id VARCHAR(40) NOT NULL,
  pipeline_id VARCHAR(8) NOT NULL,
  trade_date CHAR(8) NOT NULL,
  trigger_type VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  retry_wait_until DATETIME NULL,
  item_total INT NOT NULL DEFAULT 0,
  item_success INT NOT NULL DEFAULT 0,
  item_failed INT NOT NULL DEFAULT 0,
  started_at DATETIME NULL,
  finished_at DATETIME NULL,
  error_msg VARCHAR(1024) NULL,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pipeline_run ON pipeline_run (run_id);
CREATE INDEX IF NOT EXISTS idx_pipeline_run_status ON pipeline_run (status, created_at);


-- 流水线层：编排内单个标的执行明细
CREATE TABLE IF NOT EXISTS pipeline_run_item (
  -- [原 MySQL 表注释] 流水线层-编排标的明细
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id VARCHAR(40) NOT NULL,
  ts_code VARCHAR(12) NOT NULL,
  unit_id VARCHAR(40) NULL,
  stage VARCHAR(16) NULL,
  status VARCHAR(16) NOT NULL,
  fact_summary TEXT NULL,
  degraded TINYINT NOT NULL DEFAULT 0,
  duration_ms BIGINT NULL,
  error_msg VARCHAR(1024) NULL,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_pipeline_run_item_run ON pipeline_run_item (run_id);
CREATE INDEX IF NOT EXISTS idx_pipeline_run_item_ts ON pipeline_run_item (ts_code, created_at);


-- 流水线层：触发队列
CREATE TABLE IF NOT EXISTS pipeline_trigger (
  -- [原 MySQL 表注释] 流水线层-触发队列（每分钟轮询消费）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  trigger_id VARCHAR(40) NOT NULL,
  trigger_type VARCHAR(16) NOT NULL,
  pipeline_id VARCHAR(8) NULL,
  payload TEXT NULL,
  execute_at DATETIME NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  consumed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pipeline_trigger ON pipeline_trigger (trigger_id);
CREATE INDEX IF NOT EXISTS idx_pipeline_trigger_poll ON pipeline_trigger (status, execute_at);


-- 报告层：报告元数据（正文在磁盘）
CREATE TABLE IF NOT EXISTS report (
  -- [原 MySQL 表注释] 报告层-报告元数据（report_no 唯一）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_no VARCHAR(40) NOT NULL,
  run_id VARCHAR(40) NULL,
  ts_code VARCHAR(12) NULL,
  trade_date CHAR(8) NULL,
  report_type VARCHAR(16) NOT NULL,
  title VARCHAR(255) NULL,
  file_path VARCHAR(512) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'READY',
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_report ON report (report_no);
CREATE INDEX IF NOT EXISTS idx_report_ts ON report (ts_code, trade_date);


-- 版本层：能力单元版本（快照式，只增不改）
CREATE TABLE IF NOT EXISTS unit_version (
  -- [原 MySQL 表注释] 版本层-能力单元版本（幂等键 unit_id+version）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  unit_id VARCHAR(40) NOT NULL,
  version VARCHAR(20) NOT NULL,
  change_note VARCHAR(512) NULL,
  prompt_path VARCHAR(255) NULL,
  params TEXT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime')),
  activated_at DATETIME NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_unit_version ON unit_version (unit_id, version);


-- 版本层：方法版本
CREATE TABLE IF NOT EXISTS method_version (
  -- [原 MySQL 表注释] 版本层-方法版本（version 唯一）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version VARCHAR(20) NOT NULL,
  description VARCHAR(512) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime')),
  activated_at DATETIME NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_method_version ON method_version (version);


-- 版本层：方法版本变更记录
CREATE TABLE IF NOT EXISTS method_version_changelog (
  -- [原 MySQL 表注释] 版本层-方法版本变更记录
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  method_version VARCHAR(20) NOT NULL,
  unit_id VARCHAR(40) NOT NULL,
  unit_version VARCHAR(20) NOT NULL,
  change_type VARCHAR(16) NOT NULL,
  change_detail VARCHAR(1024) NULL,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_method_version_changelog_version ON method_version_changelog (method_version);


-- 系统层：数据接入作业日志（T4 编排与原型作业历史）
CREATE TABLE IF NOT EXISTS ingest_log (
  -- [原 MySQL 表注释] 系统层-数据接入作业日志（ingest_log 状态机 PENDING→RUNNING→SUCCESS/FAILED/SKIPPED）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id VARCHAR(40) NOT NULL,
  table_name VARCHAR(64) NOT NULL,
  job_type VARCHAR(16) NOT NULL,
  range_params VARCHAR(255) NULL,
  status VARCHAR(16) NOT NULL,
  rows_fetched BIGINT NOT NULL DEFAULT 0,
  rows_written BIGINT NOT NULL DEFAULT 0,
  retry_count INT NOT NULL DEFAULT 0,
  start_time DATETIME NULL,
  end_time DATETIME NULL,
  duration_ms BIGINT NULL,
  error_msg VARCHAR(1024) NULL,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_ingest_log_table ON ingest_log (table_name, created_at);
CREATE INDEX IF NOT EXISTS idx_ingest_log_run ON ingest_log (run_id);
CREATE INDEX IF NOT EXISTS idx_ingest_log_status ON ingest_log (status, created_at);


-- 系统层：限流分钟计数（自然分钟窗口旁路计数）
CREATE TABLE IF NOT EXISTS rate_limit_counter (
  -- [原 MySQL 表注释] 系统层-限流分钟计数（幂等键 api_name+minute_slot）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  api_name VARCHAR(64) NOT NULL,
  minute_slot VARCHAR(14) NOT NULL,
  call_count INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rate_limit_counter ON rate_limit_counter (api_name, minute_slot);


-- 系统层：LLM 调用用量
CREATE TABLE IF NOT EXISTS llm_usage (
  -- [原 MySQL 表注释] 系统层-LLM 调用用量（成本控制）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id VARCHAR(40) NULL,
  unit_id VARCHAR(40) NULL,
  -- 模型档位 fast / strong（T7 用量记账维度）
  model_profile VARCHAR(16) NULL,
  model VARCHAR(64) NOT NULL,
  prompt_tokens INT NOT NULL DEFAULT 0,
  completion_tokens INT NOT NULL DEFAULT 0,
  total_tokens INT NOT NULL DEFAULT 0,
  cost DECIMAL(12,6) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_llm_usage_created ON llm_usage (created_at);
CREATE INDEX IF NOT EXISTS idx_llm_usage_run ON llm_usage (run_id);


-- 系统层：系统配置（含单表每日增量开关 ingest.daily.<table>）
CREATE TABLE IF NOT EXISTS sys_config (
  -- [原 MySQL 表注释] 系统层-系统配置（config_key 唯一）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  config_key VARCHAR(128) NOT NULL,
  config_value VARCHAR(1024) NULL,
  remark VARCHAR(255) NULL,
  updated_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_config ON sys_config (config_key);


-- 账号域：用户账号（username 唯一；多用户，role 见 UserRole 枚举）
CREATE TABLE IF NOT EXISTS sys_user (
  -- [原 MySQL 表注释] 账号域-用户账号（username 唯一；role 枚举 ADMIN/USER，active 0/1）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(64) NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'USER',
  active TINYINT NOT NULL DEFAULT 1,
  last_login_at DATETIME NULL,
  remark VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime')),
  updated_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user ON sys_user (username);


-- 账号域：登录会话（token_hash 唯一；只存 SHA-256 哈希，不存明文令牌）
CREATE TABLE IF NOT EXISTS sys_user_session (
  -- [原 MySQL 表注释] 账号域-登录会话（token_hash 唯一；revoked 0/1，过期由应用层判定）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id BIGINT NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  issued_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  last_used_at DATETIME NULL,
  revoked TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime')),
  updated_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_session ON sys_user_session (token_hash);
CREATE INDEX IF NOT EXISTS idx_sys_user_session_user ON sys_user_session (user_id, revoked);


-- 账号域：用户交易偏好（user_id+pref_key 唯一；value 为字符串，复杂结构用 JSON 文本）
CREATE TABLE IF NOT EXISTS user_preference (
  -- [原 MySQL 表注释] 账号域-用户交易偏好（幂等键 user_id+pref_key；value 单位/口径由 pref_key 约定）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id BIGINT NOT NULL,
  pref_key VARCHAR(64) NOT NULL,
  pref_value VARCHAR(1024) NULL,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime')),
  updated_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_preference ON user_preference (user_id, pref_key);


-- 账号域：用户自选股（user_id+ts_code 唯一；按用户隔离的股票选择）
CREATE TABLE IF NOT EXISTS user_watchlist (
  -- [原 MySQL 表注释] 账号域-用户自选股（幂等键 user_id+ts_code；stock_name 为写入时快照）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id BIGINT NOT NULL,
  ts_code VARCHAR(12) NOT NULL,
  stock_name VARCHAR(128) NULL,
  group_name VARCHAR(32) NULL,
  note VARCHAR(255) NULL,
  sort_no INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT (datetime('now','localtime')),
  updated_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_watchlist ON user_watchlist (user_id, ts_code);
CREATE INDEX IF NOT EXISTS idx_user_watchlist_sort ON user_watchlist (user_id, sort_no, id);


-- 系统层：数据质量门检查历史（原型 S0 历史需要，对《架构》§5.1 的最小必要补充）
CREATE TABLE IF NOT EXISTS quality_check_log (
  -- [原 MySQL 表注释] 系统层-数据质量门检查历史
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  check_time DATETIME NOT NULL,
  trigger_type VARCHAR(16) NOT NULL,
  result VARCHAR(16) NOT NULL,
  items TEXT NOT NULL,
  duration_ms BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_quality_check_log_time ON quality_check_log (check_time);

