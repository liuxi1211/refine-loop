package com.arthur.refineloop.tushare.ingest.registry;

import com.arthur.refineloop.tushare.ingest.model.TableDef;
import com.arthur.refineloop.tushare.ingest.model.TableGroup;
import com.arthur.refineloop.tushare.ingest.model.WriteMode;
import com.arthur.refineloop.tushare.ingest.model.IngestStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tushare 表注册表（41 条，顺序严格等于《T2~T5 开发协作契约》§4.2 表格顺序）。
 *
 * <p>本类是 41 张 Tushare 表的唯一元数据来源：表名/api_name/中文名/分组/fields 列串/更新策略/
 * 写入模式/幂等键/分页规则/官方限流/最新日期列/代码列/单位口径/特殊标记（{@code options}）。
 * schema（db/schema.sql）的列与这里的 {@code fields} 必须逐列一致，由 RegistrySchemaConsistencyTest 守护。
 *
 * <p>{@code options} 取值约定（T4 读取）：{@code stock_status}、{@code exchanges}、{@code freq}、
 * {@code codeSource}、{@code param}、{@code paramSrc}、{@code indexCodeLoop}、{@code dedup}、
 * {@code special}、{@code disabledReason}；无特殊标记为不可变空 Map。
 */
public final class TushareTableRegistry {

    /** 41 张 Tushare 表定义（顺序 = 契约 §4.2）。 */
    public static final List<TableDef> ALL;

    static {
        List<TableDef> defs = new ArrayList<>(41);
        defs.add(def(
                "stock_basic",
                "stock_basic",
                "股票基础信息",
                TableGroup.DIMENSION,
                IngestStrategy.ONE_SHOT,
                WriteMode.FULL_REPLACE,
                List.of("ts_code"),
                5000, 10, 500,
                "list_date",
                "ts_code",
                "-",
                "ts_code,symbol,name,area,industry,fullname,enname,cnspell,market,exchange,curr_type,list_status,list_date,delist_date,is_hs,act_name,act_ent_type",
                Map.of("stock_status", "L,D,P")));
        defs.add(def(
                "trade_cal",
                "trade_cal",
                "交易日历",
                TableGroup.DIMENSION,
                IngestStrategy.ONE_SHOT,
                WriteMode.UPSERT,
                List.of("exchange", "cal_date"),
                5000, 10, 500,
                "cal_date",
                "exchange",
                "-",
                "exchange,cal_date,is_open,pretrade_date",
                Map.of("exchanges", "SSE,SZSE")));
        defs.add(def(
                "stock_namechange",
                "namechange",
                "股票曾用名",
                TableGroup.DIMENSION,
                IngestStrategy.PER_MONTH,
                WriteMode.UPSERT,
                List.of("ts_code", "start_date"),
                5000, 10, 200,
                "start_date",
                "ts_code",
                "-",
                "ts_code,name,start_date,end_date,change_reason",
                Map.of()));
        defs.add(def(
                "daily",
                "daily",
                "日线行情",
                TableGroup.QUOTE,
                IngestStrategy.PER_TRADE_DATE,
                WriteMode.UPSERT,
                List.of("ts_code", "trade_date"),
                10000, 10, 500,
                "trade_date",
                "ts_code",
                "vol=手 amount=千元 价格=元 pct_chg=%",
                "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount",
                Map.of()));
        defs.add(def(
                "adj_factor",
                "adj_factor",
                "复权因子",
                TableGroup.QUOTE,
                IngestStrategy.PER_TRADE_DATE,
                WriteMode.UPSERT,
                List.of("ts_code", "trade_date"),
                10000, 10, 200,
                "trade_date",
                "ts_code",
                "-",
                "ts_code,trade_date,adj_factor",
                Map.of()));
        defs.add(def(
                "daily_basic",
                "daily_basic",
                "每日指标",
                TableGroup.QUOTE,
                IngestStrategy.PER_TRADE_DATE,
                WriteMode.UPSERT,
                List.of("trade_date", "ts_code"),
                10000, 10, 180,
                "trade_date",
                "ts_code",
                "total_mv/circ_mv=万元 total_share/float_share/free_share=万股 换手率/股息率=%",
                "ts_code,trade_date,close,turnover_rate,turnover_rate_f,volume_ratio,pe,pe_ttm,pb,ps,ps_ttm,dv_ratio,dv_ttm,total_share,float_share,free_share,total_mv,circ_mv",
                Map.of()));
        defs.add(def(
                "stock_moneyflow",
                "moneyflow",
                "个股资金流向",
                TableGroup.QUOTE,
                IngestStrategy.PER_TRADE_DATE,
                WriteMode.UPSERT,
                List.of("ts_code", "trade_date"),
                10000, 10, 180,
                "trade_date",
                "ts_code",
                "金额=万元 量=万手",
                "ts_code,trade_date,buy_sm_amount,sell_sm_amount,buy_sm_vol,sell_sm_vol,buy_md_amount,sell_md_amount,buy_md_vol,sell_md_vol,buy_lg_amount,sell_lg_amount,buy_lg_vol,sell_lg_vol,buy_elg_amount,sell_elg_amount,buy_elg_vol,sell_elg_vol,net_mf_amount,net_mf_vol",
                Map.of()));
        defs.add(def(
                "stock_stk_limit",
                "stk_limit",
                "每日涨跌停价格",
                TableGroup.QUOTE,
                IngestStrategy.PER_MONTH,
                WriteMode.UPSERT,
                List.of("ts_code", "trade_date"),
                5000, 100, 180,
                "trade_date",
                "ts_code",
                "价格=元",
                "ts_code,trade_date,pre_close,up_limit,down_limit",
                Map.of()));
        defs.add(def(
                "stock_weekly_quote",
                "stk_weekly_monthly",
                "周线行情",
                TableGroup.QUOTE,
                IngestStrategy.PER_MONTH,
                WriteMode.UPSERT,
                List.of("ts_code", "trade_date"),
                5000, 100, 200,
                "trade_date",
                "ts_code",
                "vol=手 amount=千元 价格=元（实测不换算）",
                "ts_code,trade_date,end_date,freq,open,high,low,close,pre_close,vol,amount,change,pct_chg",
                Map.of("freq", "week")));
        defs.add(def(
                "stock_monthly_quote",
                "stk_weekly_monthly",
                "月线行情",
                TableGroup.QUOTE,
                IngestStrategy.PER_MONTH,
                WriteMode.UPSERT,
                List.of("ts_code", "trade_date"),
                5000, 100, 200,
                "trade_date",
                "ts_code",
                "vol=手 amount=千元 价格=元（实测不换算）",
                "ts_code,trade_date,end_date,freq,open,high,low,close,pre_close,vol,amount,change,pct_chg",
                Map.of("freq", "month")));
        defs.add(def(
                "dividend",
                "dividend",
                "分红送股",
                TableGroup.FINANCE,
                IngestStrategy.PER_STOCK,
                WriteMode.UPSERT,
                List.of("ts_code", "end_date", "ann_date"),
                5000, 10, 180,
                "ann_date",
                "ts_code",
                "cash_div=元/股 stk_div=股/股 base_share=万股",
                "ts_code,end_date,ann_date,div_proc,stk_div,stk_bo_rate,stk_co_rate,cash_div,cash_div_tax,record_date,ex_date,pay_date,div_listdate,imp_ann_date,base_date,base_share",
                Map.of()));
        defs.add(def(
                "fina_indicator",
                "fina_indicator",
                "财务指标",
                TableGroup.FINANCE,
                IngestStrategy.PER_STOCK,
                WriteMode.UPSERT,
                List.of("ts_code", "end_date"),
                5000, 10, 150,
                "ann_date",
                "ts_code",
                "比率为 %",
                "ts_code,ann_date,end_date,roe,roa,grossprofit_margin,netprofit_margin,dt_netprofit_yoy,or_yoy,debt_to_assets,dt_eps_yoy",
                Map.of()));
        defs.add(def(
                "income",
                "income",
                "利润表",
                TableGroup.FINANCE,
                IngestStrategy.PER_STOCK,
                WriteMode.UPSERT,
                List.of("ts_code", "end_date", "report_type"),
                5000, 10, 200,
                "ann_date",
                "ts_code",
                "金额=元 EPS=元/股",
                "ts_code,ann_date,f_ann_date,end_date,report_type,comp_type,end_type,basic_eps,diluted_eps,total_revenue,revenue,int_income,prem_earned,comm_income,n_commis_income,n_oth_income,n_oth_b_income,prem_income,out_prem,une_prem_reser,reins_income,n_sec_tb_income,n_sec_uw_income,n_asset_mg_income,oth_b_income,fv_value_chg_gain,invest_income,ass_invest_income,forex_gain,total_cogs,oper_cost,int_exp,comm_exp,biz_tax_surchg,sell_exp,admin_exp,fin_exp,assets_impair_loss,prem_refund,compens_payout,reser_insur_liab,div_payt,reins_exp,oper_exp,compens_payout_refu,insur_reser_refu,reins_cost_refund,other_bus_cost,operate_profit,non_oper_income,non_oper_exp,nca_disploss,total_profit,income_tax,n_income,n_income_attr_p,minority_gain,oth_compr_income,t_compr_income,compr_inc_attr_p,compr_inc_attr_m_s,ebit,ebitda,insurance_exp,undist_profit,distable_profit,rd_exp,fin_exp_int_exp,fin_exp_int_inc,transfer_surplus_rese,transfer_housing_imprest,transfer_oth,adj_lossgain,withdra_legal_surplus,withdra_legal_pubfund,withdra_biz_devfund,withdra_rese_fund,withdra_oth_ersu,workers_welfare,distr_profit_shrhder,prfshare_payable_dvd,comshare_payable_dvd,capit_comstock_div,net_after_nr_lp_correct,credit_impa_loss,net_expo_hedging_benefits,oth_impair_loss_assets,total_opcost,amodcost_fin_assets,oth_income,asset_disp_income,continued_net_profit,end_net_profit,update_flag",
                Map.of("dedup", "keep_latest_ann_date")));
        defs.add(def(
                "balancesheet",
                "balancesheet",
                "资产负债表",
                TableGroup.FINANCE,
                IngestStrategy.PER_STOCK,
                WriteMode.UPSERT,
                List.of("ts_code", "end_date", "report_type"),
                5000, 10, 200,
                "ann_date",
                "ts_code",
                "金额=元",
                "ts_code,ann_date,f_ann_date,end_date,report_type,comp_type,end_type,total_share,cap_rese,undistr_porfit,surplus_rese,special_rese,money_cap,trad_asset,notes_receiv,accounts_receiv,oth_receiv,prepayment,div_receiv,int_receiv,inventories,amor_exp,nca_within_1y,sett_rsrv,loanto_oth_bank_fi,premium_receiv,reinsur_receiv,reinsur_res_receiv,pur_resale_fa,oth_cur_assets,total_cur_assets,fa_avail_for_sale,htm_invest,lt_eqt_invest,invest_real_estate,time_deposits,oth_assets,lt_rec,fix_assets,cip,const_materials,fixed_assets_disp,produc_bio_assets,oil_and_gas_assets,intan_assets,r_and_d,goodwill,lt_amor_exp,defer_tax_assets,decr_in_disbur,oth_nca,total_nca,cash_reser_cb,depos_in_oth_bfi,prec_metals,deriv_assets,rr_reins_une_prem,rr_reins_outstd_cla,rr_reins_lins_liab,rr_reins_lthins_liab,refund_depos,ph_pledge_loans,refund_cap_depos,indep_acct_assets,client_depos,client_prov,transac_seat_fee,invest_as_receiv,total_assets,lt_borr,st_borr,cb_borr,depos_ib_deposits,loan_oth_bank,trading_fl,notes_payable,acct_payable,adv_receipts,sold_for_repur_fa,comm_payable,payroll_payable,taxes_payable,int_payable,div_payable,oth_payable,acc_exp,deferred_inc,st_bonds_payable,payable_to_reinsurer,rsrv_insur_cont,acting_trading_sec,acting_uw_sec,non_cur_liab_due_1y,oth_cur_liab,total_cur_liab,bond_payable,lt_payable,specific_payables,estimated_liab,defer_tax_liab,defer_inc_non_cur_liab,oth_ncl,total_ncl,depos_oth_bfi,deriv_liab,depos,agency_bus_liab,oth_liab,prem_receiv_adva,depos_received,ph_invest,reser_une_prem,reser_outstd_claims,reser_lins_liab,reser_lthins_liab,indept_acc_liab,pledge_borr,indem_payable,policy_div_payable,total_liab,treasury_share,ordin_risk_reser,forex_differ,invest_loss_unconf,minority_int,total_hldr_eqy_exc_min_int,total_hldr_eqy_inc_min_int,total_liab_hldr_eqy,lt_payroll_payable,oth_comp_income,oth_eqt_tools,oth_eqt_tools_p_shr,lending_funds,acc_receivable,st_fin_payable,payables,hfs_assets,hfs_sales,cost_fin_assets,fair_value_fin_assets,cip_total,oth_pay_total,long_pay_total,debt_invest,oth_debt_invest,oth_eq_invest,oth_illiq_fin_assets,oth_eq_ppbond,receiv_financing,use_right_assets,lease_liab,contract_assets,contract_liab,accounts_receiv_bill,accounts_pay,oth_rcv_total,fix_assets_total,update_flag",
                Map.of("dedup", "keep_latest_ann_date")));
        defs.add(def(
                "cashflow",
                "cashflow",
                "现金流量表",
                TableGroup.FINANCE,
                IngestStrategy.PER_STOCK,
                WriteMode.UPSERT,
                List.of("ts_code", "end_date", "report_type"),
                5000, 10, 200,
                "ann_date",
                "ts_code",
                "金额=元",
                "ts_code,ann_date,f_ann_date,end_date,comp_type,report_type,end_type,net_profit,finan_exp,c_fr_sale_sg,recp_tax_rends,n_depos_incr_fi,n_incr_loans_cb,n_inc_borr_oth_fi,prem_fr_orig_contr,n_incr_insured_dep,n_reinsur_prem,n_incr_disp_tfa,ifc_cash_incr,n_incr_disp_faas,n_incr_loans_oth_bank,n_cap_incr_repur,c_fr_oth_operate_a,c_inf_fr_operate_a,c_paid_goods_s,c_paid_to_for_empl,c_paid_for_taxes,n_incr_clt_loan_adv,n_incr_dep_cbob,c_pay_claims_orig_inco,pay_handling_chrg,pay_comm_insur_plcy,oth_cash_pay_oper_act,st_cash_out_act,n_cashflow_act,oth_recp_ral_inv_act,c_disp_withdrwl_invest,c_recp_return_invest,n_recp_disp_fiolta,n_recp_disp_sobu,stot_inflows_inv_act,c_pay_acq_const_fiolta,c_paid_invest,n_disp_subs_oth_biz,oth_pay_ral_inv_act,n_incr_pledge_loan,stot_out_inv_act,n_cashflow_inv_act,c_recp_borrow,proc_issue_bonds,oth_cash_recp_ral_fnc_act,stot_cash_in_fnc_act,free_cashflow,c_prepay_amt_borr,c_pay_dist_dpcp_int_exp,incl_dvd_profit_paid_sc_ms,oth_cashpay_ral_fnc_act,stot_cashout_fnc_act,n_cash_flows_fnc_act,eff_fx_flu_cash,n_incr_cash_cash_equ,c_cash_equ_beg_period,c_cash_equ_end_period,c_recp_cap_contrib,incl_cash_rec_saims,uncon_invest_loss,prov_depr_assets,depr_fa_coga_dpba,amort_intang_assets,lt_amort_deferred_exp,decr_deferred_exp,incr_acc_exp,loss_disp_fiolta,loss_scr_fa,loss_fv_chg,invest_loss,decr_def_inc_tax_assets,incr_def_inc_tax_liab,decr_inventories,decr_oper_payable,incr_oper_payable,others,im_net_cashflow_oper_act,conv_debt_into_cap,conv_copbonds_due_within_1y,fa_fnc_leases,im_n_incr_cash_equ,net_dism_capital_add,net_cash_rece_sec,credit_impa_loss,use_right_asset_dep,oth_loss_asset,end_bal_cash,beg_bal_cash,end_bal_cash_equ,beg_bal_cash_equ,update_flag",
                Map.of("dedup", "keep_latest_ann_date")));
        defs.add(def(
                "forecast",
                "forecast",
                "业绩预告",
                TableGroup.FINANCE,
                IngestStrategy.PER_STOCK,
                WriteMode.UPSERT,
                List.of("ts_code", "end_date", "ann_date"),
                5000, 10, 200,
                "ann_date",
                "ts_code",
                "p_change=%, net_profit=万元",
                "ts_code,ann_date,end_date,type,p_change_min,p_change_max,net_profit_min,net_profit_max,last_parent_net,summary,change_reason",
                Map.of()));
        defs.add(def(
                "express",
                "express",
                "业绩快报",
                TableGroup.FINANCE,
                IngestStrategy.PER_STOCK,
                WriteMode.UPSERT,
                List.of("ts_code", "end_date"),
                5000, 10, 190,
                "ann_date",
                "ts_code",
                "金额=元 比率=%",
                "ts_code,ann_date,end_date,revenue,operate_profit,total_profit,n_income,total_assets,total_hldr_eqy_exc_min_int,diluted_eps,diluted_roe,yoy_net_profit,bps,perf_summary,update_flag",
                Map.of()));
        defs.add(def(
                "fina_audit",
                "fina_audit",
                "财务审计意见",
                TableGroup.FINANCE,
                IngestStrategy.PER_STOCK,
                WriteMode.UPSERT,
                List.of("ts_code", "end_date", "ann_date"),
                5000, 10, 150,
                "ann_date",
                "ts_code",
                "audit_fees=元",
                "ts_code,ann_date,end_date,audit_result,audit_fees,audit_agency,audit_sign",
                Map.of()));
        defs.add(def(
                "fina_mainbz",
                "fina_mainbz",
                "主营业务构成",
                TableGroup.FINANCE,
                IngestStrategy.PER_STOCK,
                WriteMode.UPSERT,
                List.of("ts_code", "end_date", "bz_item", "bz_code"),
                100, 100, 150,
                "end_date",
                "ts_code",
                "金额=元",
                "ts_code,end_date,bz_item,bz_code,bz_sales,bz_profit,bz_cost,curr_type,update_flag",
                Map.of("special", "fina_mainbz_per_stock_full_history")));
        defs.add(def(
                "disclosure_date",
                "disclosure_date",
                "财报披露计划",
                TableGroup.FINANCE,
                IngestStrategy.PER_PERIOD,
                WriteMode.UPSERT,
                List.of("ts_code", "end_date"),
                6000, 10, 300,
                "end_date",
                "ts_code",
                "-",
                "ts_code,ann_date,end_date,pre_date,actual_date,modify_date",
                Map.of("special", "quarter_end")));
        defs.add(def(
                "top_list",
                "top_list",
                "龙虎榜每日明细",
                TableGroup.EVENT,
                IngestStrategy.PER_TRADE_DATE,
                WriteMode.BLOCK_DELETE_INSERT,
                List.of("trade_date", "ts_code", "name", "reason"),
                5000, 10, 180,
                "trade_date",
                "ts_code",
                "close=元 amount=元 比率为%",
                "trade_date,ts_code,name,close,pct_change,turnover_rate,amount,l_sell,l_buy,l_amount,net_amount,net_rate,amount_rate,float_values,reason",
                Map.of()));
        defs.add(def(
                "top_inst",
                "top_inst",
                "龙虎榜机构明细",
                TableGroup.EVENT,
                IngestStrategy.PER_TRADE_DATE,
                WriteMode.UPSERT,
                List.of("trade_date", "ts_code", "exalter", "side"),
                5000, 10, 180,
                "trade_date",
                "ts_code",
                "buy/sell/net_buy=元 比率为%",
                "trade_date,ts_code,exalter,side,buy,buy_rate,sell,sell_rate,net_buy",
                Map.of()));
        defs.add(def(
                "block_trade",
                "block_trade",
                "大宗交易",
                TableGroup.EVENT,
                IngestStrategy.PER_TRADE_DATE,
                WriteMode.UPSERT,
                List.of("trade_date", "ts_code", "buyer", "seller"),
                5000, 10, 180,
                "trade_date",
                "ts_code",
                "vol=万股 amount=万元 价格=元",
                "ts_code,trade_date,price,vol,amount,buyer,seller",
                Map.of()));
        defs.add(def(
                "stock_suspend_d",
                "suspend_d",
                "每日停复牌",
                TableGroup.EVENT,
                IngestStrategy.PER_MONTH,
                WriteMode.UPSERT,
                List.of("ts_code", "trade_date", "suspend_type"),
                5000, 100, 200,
                "trade_date",
                "ts_code",
                "-",
                "ts_code,trade_date,suspend_timing,suspend_type",
                Map.of("special", "offset_limit_fallback_per_stock")));
        defs.add(def(
                "share_float",
                "share_float",
                "限售股解禁",
                TableGroup.EVENT,
                IngestStrategy.PER_MONTH,
                WriteMode.UPSERT,
                List.of("ts_code", "float_date", "holder_name"),
                5000, 100, 60,
                "float_date",
                "ts_code",
                "float_share=股 float_ratio=%",
                "ts_code,ann_date,float_date,float_share,float_ratio,holder_name,share_type",
                Map.of()));
        defs.add(def(
                "pledge_stat",
                "pledge_stat",
                "股权质押统计",
                TableGroup.EVENT,
                IngestStrategy.PER_PERIOD,
                WriteMode.UPSERT,
                List.of("ts_code", "end_date"),
                1000, 100, 60,
                "end_date",
                "ts_code",
                "质押数量=万股 比例=%",
                "ts_code,end_date,pledge_count,unrest_pledge,rest_pledge,total_share,pledge_ratio",
                Map.of("special", "friday_window")));
        defs.add(def(
                "pledge_detail",
                "pledge_detail",
                "股权质押明细",
                TableGroup.EVENT,
                IngestStrategy.PER_STOCK,
                WriteMode.BLOCK_DELETE_INSERT,
                List.of("ts_code", "ann_date"),
                1000, 100, 60,
                "ann_date",
                "ts_code",
                "质押数量=万股 比例=%",
                "ts_code,ann_date,holder_name,pledge_amount,start_date,end_date,is_release,release_date,pledgor,holding_amount,pledged_amount,p_total_ratio,h_total_ratio,is_buyback",
                Map.of()));
        defs.add(def(
                "repurchase",
                "repurchase",
                "股票回购",
                TableGroup.EVENT,
                IngestStrategy.PER_MONTH,
                WriteMode.BLOCK_DELETE_INSERT,
                List.of("ts_code", "ann_date"),
                2000, 100, 60,
                "ann_date",
                "ts_code",
                "vol=万股 amount=万元 价格=元",
                "ts_code,ann_date,end_date,proc,exp_date,vol,amount,high_limit,low_limit",
                Map.of()));
        defs.add(def(
                "top10_holders",
                "top10_holders",
                "前十大股东",
                TableGroup.EVENT,
                IngestStrategy.PER_STOCK,
                WriteMode.BLOCK_DELETE_INSERT,
                List.of("ts_code", "end_date"),
                5000, 10, 120,
                "end_date",
                "ts_code",
                "hold_amount=股 比例=%",
                "ts_code,ann_date,end_date,holder_name,hold_amount,hold_ratio,hold_float_ratio,hold_change,holder_type",
                Map.of()));
        defs.add(def(
                "top10_floatholders",
                "top10_floatholders",
                "前十大流通股东",
                TableGroup.EVENT,
                IngestStrategy.PER_STOCK,
                WriteMode.BLOCK_DELETE_INSERT,
                List.of("ts_code", "end_date"),
                5000, 10, 120,
                "end_date",
                "ts_code",
                "hold_amount=股 比例=%",
                "ts_code,ann_date,end_date,holder_name,hold_amount,hold_ratio,hold_float_ratio,hold_change,holder_type",
                Map.of()));
        defs.add(def(
                "stk_holdertrade",
                "stk_holdertrade",
                "股东增减持",
                TableGroup.EVENT,
                IngestStrategy.PER_STOCK,
                WriteMode.UPSERT,
                List.of("ts_code", "ann_date", "holder_name", "in_de", "begin_date", "close_date"),
                5000, 10, 180,
                "ann_date",
                "ts_code",
                "change_vol=万股 比例=%",
                "ts_code,ann_date,holder_name,holder_type,in_de,change_vol,change_ratio,after_share,after_ratio,avg_price,total_share,begin_date,close_date",
                Map.of()));
        defs.add(def(
                "stk_holdernumber",
                "stk_holdernumber",
                "股东户数",
                TableGroup.EVENT,
                IngestStrategy.PER_STOCK,
                WriteMode.UPSERT,
                List.of("ts_code", "end_date"),
                5000, 10, 180,
                "end_date",
                "ts_code",
                "holder_num=户",
                "ts_code,ann_date,end_date,holder_num",
                Map.of()));
        defs.add(def(
                "report_rc",
                "report_rc",
                "券商盈利预测",
                TableGroup.EVENT,
                IngestStrategy.PER_PERIOD,
                WriteMode.BLOCK_DELETE_INSERT,
                List.of("ts_code", "report_date", "org_name"),
                3000, 1, 1,
                "report_date",
                "ts_code",
                "op_rt/op_pr/tp/np=万元 比率为%",
                "ts_code,name,report_date,report_title,report_type,classify,org_name,author_name,quarter,op_rt,op_pr,tp,np,eps,pe,rd,roe,ev_ebitda,rating,max_price,min_price,imp_dg,create_time",
                Map.of("special", "single_page_recent_90d")));
        defs.add(def(
                "index_basic",
                "index_basic",
                "指数基础信息",
                TableGroup.DIMENSION,
                IngestStrategy.ONE_SHOT,
                WriteMode.FULL_REPLACE,
                List.of("ts_code"),
                5000, 10, 500,
                "list_date",
                "ts_code",
                "-",
                "ts_code,name,fullname,market,publisher,index_type,category,base_date,base_point,list_date,weight_rule",
                Map.of()));
        defs.add(def(
                "index_daily",
                "index_daily",
                "指数日线行情",
                TableGroup.QUOTE,
                IngestStrategy.PER_STOCK,
                WriteMode.UPSERT,
                List.of("ts_code", "trade_date"),
                5000, 10, 500,
                "trade_date",
                "ts_code",
                "vol=手 amount=千元 点位（实测与 daily 同口径）",
                "ts_code,trade_date,close,open,high,low,pre_close,change,pct_chg,vol,amount",
                Map.of("codeSource", "index_basic")));
        defs.add(def(
                "index_weight",
                "index_weight",
                "指数成分权重",
                TableGroup.INDEX_MARKET,
                IngestStrategy.PER_PERIOD,
                WriteMode.UPSERT,
                List.of("index_code", "trade_date", "con_code"),
                5000, 100, 200,
                "trade_date",
                "index_code",
                "weight=%",
                "index_code,con_code,trade_date,weight",
                Map.of("codeSource", "sw_industry", "param", "index_code")));
        defs.add(def(
                "sw_industry",
                "index_classify",
                "申万行业分类",
                TableGroup.DIMENSION,
                IngestStrategy.ONE_SHOT,
                WriteMode.FULL_REPLACE,
                List.of("index_code", "src"),
                5000, 10, 200,
                null,
                "index_code",
                "-",
                "index_code,industry_name,level,industry_code,is_pub,parent_code,src",
                Map.of("paramSrc", "SW2021")));
        defs.add(def(
                "sw_industry_member",
                "index_member_all",
                "申万行业成分",
                TableGroup.DIMENSION,
                IngestStrategy.ONE_SHOT,
                WriteMode.FULL_REPLACE,
                List.of(),
                2000, 100, 200,
                "in_date",
                "ts_code",
                "-",
                "l1_code,l1_name,l2_code,l2_name,l3_code,l3_name,ts_code,name,in_date,out_date,is_new",
                Map.of("indexCodeLoop", "L1")));
        defs.add(def(
                "hk_hold",
                "hk_hold",
                "沪深港通持股",
                TableGroup.QUOTE,
                IngestStrategy.PER_TRADE_DATE,
                WriteMode.UPSERT,
                List.of("trade_date", "code"),
                10000, 10, 180,
                "trade_date",
                "code",
                "vol=万股 ratio=%",
                "trade_date,code,name,vol,ratio,ts_code,exchange",
                Map.of("disabledReason", "2024-08 起断供，M1 停用每日作业")));
        defs.add(def(
                "margin",
                "margin",
                "融资融券汇总",
                TableGroup.INDEX_MARKET,
                IngestStrategy.PER_TRADE_DATE,
                WriteMode.UPSERT,
                List.of("exchange_id", "trade_date"),
                5000, 10, 180,
                "trade_date",
                "exchange_id",
                "金额=万元 rqmcl=万股",
                "exchange_id,trade_date,rzye,rzmre,rzche,rqye,rqmcl,rzrqye",
                Map.of()));
        defs.add(def(
                "margin_detail",
                "margin_detail",
                "融资融券明细",
                TableGroup.INDEX_MARKET,
                IngestStrategy.PER_TRADE_DATE,
                WriteMode.UPSERT,
                List.of("trade_date", "ts_code"),
                10000, 10, 180,
                "trade_date",
                "ts_code",
                "金额=万元 rqmcl=万股",
                "trade_date,ts_code,name,rzye,rqye,rzmre,rzche,rqmcl,rzrqye",
                Map.of()));
        ALL = List.copyOf(defs);
    }

    private TushareTableRegistry() {
    }

    private static TableDef def(String tableName, String apiName, String chineseName, TableGroup group,
                                IngestStrategy strategy, WriteMode writeMode, List<String> idempotencyKeys,
                                int pageSize, int maxPages, int rateLimitPerMinute, String latestDateColumn,
                                String codeColumn, String unitNote, String fields, Map<String, Object> options) {
        return new TableDef(tableName, apiName, chineseName, group, fields, strategy, writeMode,
                idempotencyKeys, pageSize, maxPages, rateLimitPerMinute, latestDateColumn, codeColumn,
                unitNote, options);
    }

    /** 按表名精确查找，未注册返回 {@code null}。 */
    public static TableDef byTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return null;
        }
        for (TableDef def : ALL) {
            if (def.tableName().equals(tableName)) {
                return def;
            }
        }
        return null;
    }

    /**
     * 兜底查找：先按表名，再按唯一 api_name（stk_weekly_monthly 等一对多 api 返回 {@code null}，
     * 调用方需自行按 freq 分流）。
     */
    public static TableDef byTableAllowApi(String tableOrApi) {
        TableDef byTable = byTable(tableOrApi);
        if (byTable != null) {
            return byTable;
        }
        if (tableOrApi == null || tableOrApi.isBlank()) {
            return null;
        }
        TableDef found = null;
        for (TableDef def : ALL) {
            if (!def.apiName().equals(tableOrApi)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = def;
        }
        return found;
    }

    /** 每日增量编排应执行的表：全部 41 张中排除带 disabledReason 的表（当前仅 hk_hold）。 */
    public static List<TableDef> dailyEnabled() {
        List<TableDef> enabled = new ArrayList<>(ALL.size());
        for (TableDef def : ALL) {
            if (!def.options().containsKey("disabledReason")) {
                enabled.add(def);
            }
        }
        return List.copyOf(enabled);
    }

    /** 按分组筛选（分组内顺序保持 §4.2 顺序）。 */
    public static List<TableDef> byGroup(TableGroup group) {
        if (group == null) {
            return List.of();
        }
        List<TableDef> matched = new ArrayList<>();
        for (TableDef def : ALL) {
            if (def.group() == group) {
                matched.add(def);
            }
        }
        return List.copyOf(matched);
    }
}
