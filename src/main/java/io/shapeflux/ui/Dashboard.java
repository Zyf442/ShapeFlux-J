package io.shapeflux.ui;

import io.shapeflux.Examples;
import io.shapeflux.compiler.CompiledKernel;
import io.shapeflux.experiment.*;
import io.shapeflux.ir.*;
import io.shapeflux.runtime.*;
import io.shapeflux.io.*;
import io.shapeflux.i18n.ZhCn;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import javax.swing.table.DefaultTableModel;

/** Local Java Swing desktop; no browser, service, native dependency or network access. */
@SuppressWarnings("serial")
public final class Dashboard extends JPanel {
    static { ChineseFonts.install(); }
    private static final Color BG = new Color(0x0b1120), PANEL = new Color(0x121e32), BORDER = new Color(0x263750),
            TEXT = new Color(0xeaf1ff), MUTED = new Color(0x92a6c4), TEAL = new Color(0x42dfc0), PURPLE = new Color(0xb9a0ff);
    private final JTextArea editor = codeArea(ZhCn.dsl(Examples.ACTIVATION)), ir = codeArea("运行算子后，可在此查看中文计算图与优化结果。"),
            source = codeArea("编译完成后，可在此查看生成的 Java 源代码。"), trace = codeArea("已就绪，尚未执行请求。"), results = codeArea("运行算子或回放负载后显示实测结果。");
    private final JComboBox<String> templates = new JComboBox<>(Examples.all().keySet().toArray(String[]::new));
    private final JComboBox<Strategy> strategies = new JComboBox<>(Strategy.values());
    private final JComboBox<String> profile = new JComboBox<>(new String[]{"Conservative", "Demo horizon (16384)","Custom settings"});
    private final JComboBox<String> workloads = new JComboBox<>(new String[]{"hotset","jitter","shift","longtail"});
    private final JSpinner rows = new JSpinner(new SpinnerNumberModel(48,1,8_000_000,1)), cols = new JSpinner(new SpinnerNumberModel(128,1,8_000_000,1));
    private final JLabel status = new JLabel("已就绪 · JDK " + System.getProperty("java.version")), foot = new JLabel("本地 CPU 计算 · 双精度浮点 · 随机种子 20261027");
    private final JLabel count = metricValue(), hits = metricValue(), compiles = metricValue(), latency = metricValue();
    private final Plot plot = new Plot();
    private final ExperimentBrowser browser=new ExperimentBrowser();
    private final JTextArea reuse=codeArea("点击“轨迹分析”，选择形状CSV或实测batches.csv，查看频次、再次到达间隔和复用预测。\n随包实测轨迹在examples/traces目录。");
    private final JProgressBar progressBar=new JProgressBar(0,1000);
    private final JTabbedPane tabs;
    private final List<JButton> actions = new ArrayList<>();
    private final List<JComponent> controls = new ArrayList<>();
    private final DefaultTableModel cacheModel = new DefaultTableModel(new Object[]{"适用形状范围","字节码大小（字节）","编译耗时（毫秒）"},0) {
        @Override public boolean isCellEditable(int row,int column){return false;}
    };
    private JButton cancel;
    private volatile Thread background;
    private volatile boolean cancellationRequested;
    private EngineConfig customConfig=EngineConfig.defaults();
    private TensorInput importedMatrix;
    private List<Workloads.Shape> importedTrace;
    private Path currentProject,lastReport;
    private Run lastRun;
    private Engine engine;
    private String sessionKey = "", exportCsv = "";
    private final List<Double> history = new ArrayList<>();
    private record Run(Engine engine, List<Engine.Execution> executions, List<TensorInput> inputs,String dsl) {}
    public Dashboard() {
        super(new BorderLayout(20,18)); setBackground(BG); setBorder(new EmptyBorder(24,28,20,28));
        UIManager.put("TabbedPane.selected",new Color(0x263f5e));
        for(String key:List.of("TabbedPane.highlight","TabbedPane.light","TabbedPane.shadow","TabbedPane.darkShadow"))UIManager.put(key,BORDER);
        for (JTextArea area : List.of(ir,source,trace,results,reuse)) area.setEditable(false);
        strategies.setSelectedItem(Strategy.ADAPTIVE);
        JPanel header = new JPanel(new BorderLayout()); header.setOpaque(false);
        JPanel brand = vertical(); brand.add(label("SHAPEFLUX / J",TEXT,28,Font.BOLD)); brand.add(Box.createVerticalStrut(5));
        brand.add(label("动态形状算子 · 可观察编译与自适应优化",MUTED,11,Font.PLAIN));
        header.add(brand,BorderLayout.WEST); status.setForeground(TEAL); status.setFont(ChineseFonts.font(Font.PLAIN,13)); header.add(status,BorderLayout.EAST);
        JPanel north=new JPanel(new BorderLayout(0,12));north.setOpaque(false);north.add(header,BorderLayout.NORTH);
        JPanel tools=new JPanel(new GridLayout(2,0,6,6));tools.setOpaque(false);
        tools.add(button("打开工程",false,this::openProject));tools.add(button("保存工程",false,this::saveProject));
        tools.add(button("导入算子",false,this::importDsl));tools.add(button("导入矩阵",false,this::importMatrix));tools.add(button("导入形状",false,this::importTrace));
        tools.add(button("清除数据",false,()->{importedMatrix=null;importedTrace=null;workloads.removeItem("Custom CSV");workloads.setSelectedIndex(0);reset();foot.setText("已恢复使用固定种子生成的输入。");}));
        tools.add(button("参数设置",false,this::settings));tools.add(button("重置会话",false,this::reset));tools.add(button("查看报告",false,this::openReport));
        tools.add(button("测试随包数据",false,this::dataset));
        tools.add(button("轨迹分析",false,this::analyzeTrace));tools.add(button("矩阵乘法",false,this::matmul));tools.add(button("实验浏览",false,this::browse));
        cancel=button("取消任务",false,()->{cancellationRequested=true;Thread thread=background;if(thread!=null)thread.interrupt();cancel.setEnabled(false);status.setText("正在取消，等待当前编译或计算结束…");});
        actions.remove(cancel);cancel.setEnabled(false);tools.add(cancel);north.add(tools,BorderLayout.SOUTH);add(north,BorderLayout.NORTH);
        JPanel workspace = new JPanel(new BorderLayout(20,0)); workspace.setOpaque(false); add(workspace,BorderLayout.CENTER);
        JPanel left = vertical(); left.setBackground(PANEL); left.setOpaque(true); left.setBorder(new CompoundBorder(new LineBorder(BORDER),new EmptyBorder(18,18,18,18))); left.setPreferredSize(new Dimension(352,690));
        left.add(label("01  /  算子程序",TEAL,12,Font.BOLD)); left.add(Box.createVerticalStrut(14));
        style(templates); left.add(templates); left.add(Box.createVerticalStrut(10));
        JScrollPane inputScroll = scroll(editor); inputScroll.setPreferredSize(new Dimension(310,215)); inputScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE,275)); left.add(inputScroll); left.add(Box.createVerticalStrut(12));
        left.add(label("动态输入形状",MUTED,11,Font.BOLD)); left.add(Box.createVerticalStrut(7));
        JPanel dimensions = new JPanel(new GridLayout(1,4,8,0)); dimensions.setOpaque(false); dimensions.setAlignmentX(Component.LEFT_ALIGNMENT); dimensions.setMaximumSize(new Dimension(Integer.MAX_VALUE,34));
        dimensions.add(label("行数 N",TEXT,12,Font.PLAIN)); dimensions.add(rows); dimensions.add(label("列数 D",TEXT,12,Font.PLAIN)); dimensions.add(cols); left.add(dimensions);
        left.add(Box.createVerticalStrut(14)); left.add(label("执行策略",MUTED,11,Font.BOLD)); left.add(Box.createVerticalStrut(6)); style(strategies); left.add(strategies);
        left.add(Box.createVerticalStrut(10)); style(profile); left.add(profile); left.add(Box.createVerticalStrut(10));style(workloads);left.add(workloads);left.add(Box.createVerticalStrut(12));
        left.add(button("运行当前输入",true,()->run(false))); left.add(Box.createVerticalStrut(8)); left.add(button("回放动态负载",false,()->run(true))); left.add(Box.createVerticalStrut(8));
        JPanel secondary = new JPanel(new GridLayout(1,2,8,0)); secondary.setOpaque(false); secondary.setAlignmentX(Component.LEFT_ALIGNMENT); secondary.setMaximumSize(new Dimension(Integer.MAX_VALUE,36));
        secondary.add(button("对比执行策略",false,this::compare)); secondary.add(button("导出运行结果",false,this::export)); left.add(secondary); left.add(Box.createVerticalGlue());
        left.add(Box.createVerticalStrut(10)); left.add(label("输入矩阵与行偏置 → 融合计算内核",MUTED,10,Font.PLAIN)); JScrollPane leftPane=new JScrollPane(left);leftPane.setBorder(null);leftPane.setPreferredSize(new Dimension(365,690));leftPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);workspace.add(leftPane,BorderLayout.WEST);
        JPanel right = new JPanel(new BorderLayout(0,16)); right.setOpaque(false); workspace.add(right,BorderLayout.CENTER);
        JPanel metrics = new JPanel(new GridLayout(1,4,12,0)); metrics.setOpaque(false); metrics.setPreferredSize(new Dimension(800,96));
        metrics.add(card("请求次数",count)); metrics.add(card("缓存命中率",hits)); metrics.add(card("编译内核数",compiles)); metrics.add(card("最近计算耗时",latency)); right.add(metrics,BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(0,14)); center.setOpaque(false); right.add(center,BorderLayout.CENTER);
        Pipeline pipeline = new Pipeline(); pipeline.setPreferredSize(new Dimension(750,105)); center.add(pipeline,BorderLayout.NORTH);
        tabs=new JTabbedPane();tabs.setBackground(PANEL); tabs.setForeground(TEXT); tabs.setFont(ChineseFonts.font(Font.BOLD,13));
        tabs.addTab("计算图与优化",scroll(ir)); tabs.addTab("生成的 Java 代码",scroll(source)); tabs.addTab("运行事件日志",scroll(trace));tabs.addTab("内核缓存",new JScrollPane(new JTable(cacheModel))); tabs.addTab("运行结果",scroll(results));tabs.addTab("实验浏览",browser);tabs.addTab("轨迹与复用",scroll(reuse)); center.add(tabs,BorderLayout.CENTER);
        plot.setPreferredSize(new Dimension(780,172)); right.add(plot,BorderLayout.SOUTH);
        foot.setForeground(MUTED); foot.setFont(ChineseFonts.font(Font.PLAIN,12));
        JPanel footer=new JPanel(new BorderLayout(0,6));footer.setOpaque(false);progressBar.setStringPainted(true);progressBar.setString("就绪 · 等待任务");progressBar.setBackground(PANEL);progressBar.setForeground(TEAL);progressBar.setFont(ChineseFonts.font(Font.PLAIN,12));footer.add(progressBar,BorderLayout.NORTH);footer.add(foot,BorderLayout.SOUTH);add(footer,BorderLayout.SOUTH);
        templates.addActionListener(e->editor.setText(ZhCn.dsl(Examples.all().get(templates.getSelectedItem()))));
        controls.addAll(List.of(editor,templates,strategies,profile,rows,cols,workloads));
    }
    private void run(boolean replay) {
        String dsl = editor.getText(); Strategy strategy = (Strategy) strategies.getSelectedItem();
        EngineConfig config = currentConfig();
        int n = (Integer) rows.getValue(), d = (Integer) cols.getValue(); String key = dsl + strategy + config;
        if (replay || !key.equals(sessionKey)) { engine = null; history.clear(); sessionKey = key; }
        Engine previous = engine;
        TensorInput matrix=importedMatrix;List<Workloads.Shape> shapeTrace="Custom CSV".equals(workloads.getSelectedItem())?importedTrace:null;
        String workload=(String)workloads.getSelectedItem();
        if(replay && shapeTrace!=null && shapeTrace.stream().mapToLong(sh->(long)sh.rows()*sh.cols()+sh.cols()).sum()>32_000_000L){streamReplay(dsl,strategy,config,shapeTrace);return;}
        task(replay?"正在回放动态形状…":"正在编译并运行…",()->{
            Program parsed=Parser.parse(dsl);Engine current = previous == null ? new Engine(parsed,strategy,config) : previous;
            List<TensorInput> inputs = replay ? Workloads.inputs(shapeTrace!=null?shapeTrace:Workloads.create(workload,true),20261027) : matrix!=null?List.of(matrix):List.of(TensorInput.random(n,d,20261027));
            List<Engine.Execution> runs = new ArrayList<>();
            for (TensorInput in : inputs) {
                Engine.Execution run = current.execute(in);
                KernelProbe.interrupted();if (!Numerics.close(Interpreter.run(parsed,in),run.output())) throw new IllegalStateException("数值结果未通过参考校验");
                runs.add(run);live(new ProgressIO.Update("算子执行",runs.size(),inputs.size(),in.rows(),runs.size(),"已完成参考校验",1,1));
            }
            return new Run(current,runs,inputs,dsl);
        },this::apply);
    }
    private void streamReplay(String dsl,Strategy strategy,EngineConfig config,List<Workloads.Shape> shapes) {
        task("正在逐批重放长轨迹（冷序列与一次测量序列）…",()->{
            Path out=Path.of("results","ui-stream-"+System.currentTimeMillis());Files.createDirectories(out);Path program=out.resolve("requested-program.sfx"),traceFile=out.resolve("requested-trace.csv");Files.writeString(program,dsl,StandardCharsets.UTF_8);Workloads.writeCsv(traceFile,shapes);
            Benchmark.launch(out,new BenchmarkSettings(true,1,0,1,20261027,1800,strategy.name(),config),false,"custom",program,traceFile,this::live);
            return out;
        },out->{lastRun=null;lastReport=out.resolve("report.html");results.setText("长轨迹重放已完成。\n输入按固定种子逐批生成，未把完整轨迹矩阵同时留在内存中。\n本次执行一个冷序列和一个测量序列，当前策略为："+ZhCn.name(strategy)+"。\n点击“查看报告”查看逐请求核验、编译、派发和计时。\n\n结果目录："+out.toAbsolutePath());tabs.setSelectedIndex(4);status.setText("长轨迹重放完成");});
    }
    private void apply(Run run) {
        lastRun=run;lastReport=null;cacheModel.setRowCount(0);
        engine = run.engine; Engine.Stats s = engine.stats();
        for(CompiledKernel kernel:engine.artifacts())cacheModel.addRow(new Object[]{ZhCn.guard(kernel.guard().key()),kernel.bytecodeBytes(),String.format(Locale.ROOT,"%.3f",kernel.compileNanos()/1e6)});
        count.setText(Long.toString(s.requests())); hits.setText(String.format(Locale.ROOT,"%.0f%%",100.0*s.hits()/Math.max(1,s.requests())));
        compiles.setText(Long.toString(s.compilations())); Engine.Execution last = run.executions.get(run.executions.size()-1);
        latency.setText(String.format(Locale.ROOT,"%.1f 微秒",last.computeNanos()/1000.0));
        ir.setText(ZhCn.ir(engine.program())+"\n优化前 / 优化后节点数："+engine.optimization().before()+" / "+engine.optimization().after()+"\n常量折叠："+engine.optimization().folds()+" 处；公共表达式合并："+engine.optimization().merges()+" 处"); ir.setCaretPosition(0);
        StringBuilder java = new StringBuilder("// 以下为实际生成的 Java 内核源码。\n// 可先阅读“计算图与优化”和“运行事件日志”，理解编译过程。\n\n");
        for (CompiledKernel k : engine.artifacts()) java.append("// 适用范围：").append(ZhCn.guard(k.guard().key())).append('\n').append(k.source()).append("\n"); source.setText(java.toString()); source.setCaretPosition(0);
        StringBuilder events = new StringBuilder(); for (Engine.Event e : engine.events()) events.append(ZhCn.event(e)).append("\n"); trace.setText(events.toString()); trace.setCaretPosition(trace.getDocument().getLength());
        StringBuilder csv = new StringBuilder("rows,cols,selected,hit,compute_us,total_us\n");
        for (int i=0;i<run.executions.size();i++) { Engine.Execution e=run.executions.get(i); TensorInput in=run.inputs.get(i); history.add(e.computeNanos()/1000.0); csv.append(String.format(Locale.ROOT,"%d,%d,%s,%s,%.6f,%.6f%n",in.rows(),in.cols(),e.selected(),e.cacheHit(),e.computeNanos()/1000.0,e.totalNanos()/1000.0)); }
        exportCsv = csv.toString();
        StringBuilder readable=new StringBuilder("数值参考校验：通过\n执行策略："+ZhCn.name(engine.strategy())+"\n\n"+ZhCn.stats(s)+"\n逐请求实测记录：\n");
        for(int i=0;i<run.executions.size();i++) {
            Engine.Execution execution=run.executions.get(i);TensorInput input=run.inputs.get(i);
            readable.append("第 ").append(i+1).append(" 次｜").append(input.rows()).append(" 行 × ").append(input.cols()).append(" 列｜")
                .append(ZhCn.guard(execution.selected())).append("｜缓存").append(execution.cacheHit()?"命中":"未命中")
                .append(String.format(Locale.ROOT,"\n  计算：%.3f 微秒；请求总计：%.3f 毫秒\n",execution.computeNanos()/1000.0,execution.totalNanos()/1e6));
        }
        readable.append("\n最后一次输出预览：").append(Arrays.toString(Arrays.copyOf(last.output(),Math.min(8,last.output().length))));
        results.setText(readable.toString());results.setCaretPosition(0);
        plot.setValues(history);
        if(history.size()>256)history.subList(0,history.size()-256).clear();
        foot.setText("参考校验通过 · "+ZhCn.name(engine.strategy())+" · 累计编译 "+String.format(Locale.ROOT,"%.1f 毫秒",s.compileNanos()/1e6)+" · 请求总耗时包含编译，曲线仅显示计算耗时");
        status.setForeground(s.compileFailures()>0 || s.runtimeFailures()>0?new Color(0xffc371):TEAL);
        status.setText(s.compileFailures()>0 || s.runtimeFailures()>0?"内核出现异常，已启用回退":"参考结果校验通过");
    }
    private void browse(){browser.refresh();tabs.setSelectedIndex(5);}
    private void live(ProgressIO.Update u){SwingUtilities.invokeLater(()->{boolean known=u.total()>0;progressBar.setIndeterminate(!known);if(known)progressBar.setValue((int)Math.round(u.fraction()*1000));progressBar.setString(u.text());});}
    private void compare() {
        JSpinner forks=new JSpinner(new SpinnerNumberModel(3,1,10,1)),warmup=new JSpinner(new SpinnerNumberModel(4,0,1000,1)),measure=new JSpinner(new SpinnerNumberModel(5,1,1000,1));
        JPanel options=new JPanel(new GridLayout(0,2,8,8));options.add(new JLabel("独立进程重复数"));options.add(forks);options.add(new JLabel("预热轨迹遍数"));options.add(warmup);options.add(new JLabel("测量轨迹遍数"));options.add(measure);
        if(JOptionPane.showConfirmDialog(this,options,"重复测量与置信区间",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        int repeats=(Integer)forks.getValue(),warm=(Integer)warmup.getValue(),measured=(Integer)measure.getValue();
        String dsl = editor.getText();String workload=(String)workloads.getSelectedItem();List<Workloads.Shape> shapes="Custom CSV".equals(workload)?importedTrace:null;EngineConfig config=currentConfig();
        task("正在独立进程中对比五种策略…",()->{
            Path output = Path.of("results","ui-"+System.currentTimeMillis());Files.createDirectories(output);Path program=output.resolve("requested-program.sfx"),traceFile=null;Files.writeString(program,dsl,StandardCharsets.UTF_8);
            if(shapes!=null){traceFile=output.resolve("requested-trace.csv");Workloads.writeCsv(traceFile,shapes);}
            BenchmarkSettings settings=new BenchmarkSettings(true,repeats,warm,measured,20261027,1800,"all",config);
            Benchmark.launch(output,settings,false,workload,program,traceFile,this::live);
            return new String[]{Files.readString(output.resolve("summary.csv"),StandardCharsets.UTF_8),output.toAbsolutePath().toString(),ZhCn.benchmarkSummary(ReportWriter.read(output.resolve("summary.csv")))};
        },value->{lastRun=null;lastReport=Path.of(value[1],"report.html");exportCsv=value[0];results.setText(value[2]);results.setCaretPosition(0);tabs.setSelectedIndex(4);foot.setText("实验结果已保存："+value[1]);status.setText("策略对比已完成");});
    }
    private void export() {
        if (exportCsv.isEmpty()) { status.setText("请先运行一个算子程序"); return; }
        if(lastRun==null) {openReport();return;}
        JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if(chooser.showSaveDialog(this)==JFileChooser.APPROVE_OPTION){Path out=chooser.getSelectedFile().toPath().resolve("session-"+System.currentTimeMillis());Run snapshot=lastRun;task("正在导出运行结果…",()->{RunExporter.save(out,snapshot.dsl,Parser.parse(snapshot.dsl),snapshot.engine,snapshot.inputs,snapshot.executions);return out;},value->foot.setText("结果已导出："+value.toAbsolutePath()));}
    }
    private void dataset() {
        BundledDatasets.Profile selected=(BundledDatasets.Profile)JOptionPane.showInputDialog(this,"选择要测试的完整真实数据集：","随包数据集",JOptionPane.QUESTION_MESSAGE,null,BundledDatasets.all().toArray(),BundledDatasets.get("kdd99"));
        if(selected==null)return;Path input=selected.input();
        if(!Files.isRegularFile(input)){error(new IllegalArgumentException("找不到随包数据，请完整解压项目并使用启动脚本打开。"));return;}
        Object[] options={"快速测试（前 65,536 行）","完整测试（全部 "+String.format(Locale.ROOT,"%,d",selected.rows())+" 行）","取消"};
        int choice=JOptionPane.showOptionDialog(this,"使用当前算子公式和引擎参数，分别在独立进程中测试五种策略。\n"+selected.title()+"；偏置为零。\n每批读取约 4 千～8 千行，逐批校验结果，完成后生成中文报告。","测试随包真实数据",JOptionPane.DEFAULT_OPTION,JOptionPane.INFORMATION_MESSAGE,null,options,options[0]);
        if(choice<0 || choice>1)return;
        JSpinner forks=new JSpinner(new SpinnerNumberModel(3,1,10,1));
        if(JOptionPane.showConfirmDialog(this,new Object[]{"每种策略的独立进程重复次数（至少2次才能估计区间）：",forks},"重复测量",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        int repeats=(Integer)forks.getValue();String dsl=editor.getText();EngineConfig config=currentConfig();long limit=choice==0?65536:0;
        reset();
        task("正在准备真实数据集测试…",()->{
            DatasetBenchmark.Settings settings=new DatasetBenchmark.Settings(input,selected.label(),"4097,4352,4608,4864,5120,5376,5632,5888,6144,6400,6656,8192",limit,repeats,1800,"all",config);
            Path report=DatasetBenchmark.launch(Path.of("results","dataset-ui-"+System.currentTimeMillis()),settings,dsl,message->SwingUtilities.invokeLater(()->status.setText(message)),this::live);
            return new String[]{report.toAbsolutePath().toString(),Files.readString(report.resolveSibling("概览.txt"),StandardCharsets.UTF_8),Files.readString(report.resolveSibling("summary.csv"),StandardCharsets.UTF_8)};
        },value->{lastRun=null;lastReport=Path.of(value[0]);exportCsv=value[2];results.setText(value[1]);results.setCaretPosition(0);tabs.setSelectedIndex(4);status.setText("真实数据集测试已完成");foot.setText("点击“查看报告”打开结果："+lastReport.getParent());});
    }
    private void analyzeTrace() {
        Path input=choose("选择形状、请求或批次CSV进行轨迹分析",false,"examples/traces/recorded-covertype.csv");if(input==null)return;EngineConfig config=currentConfig();
        task("正在分析实测形状轨迹…",()->{Path report=TraceAnalyzer.analyze(input,Path.of("results","trace-ui-"+System.currentTimeMillis()),config);return new String[]{report.toAbsolutePath().toString(),Files.readString(report.resolveSibling("概览.txt"),StandardCharsets.UTF_8)};},value->{lastReport=Path.of(value[0]);reuse.setText(value[1]);reuse.setCaretPosition(0);tabs.setSelectedIndex(6);status.setText("轨迹分析完成");});
    }
    private void matmul() {
        JSpinner n=new JSpinner(new SpinnerNumberModel(64,1,4096,1)),k=new JSpinner(new SpinnerNumberModel(96,1,4096,1)),m=new JSpinner(new SpinnerNumberModel(48,1,4096,1)),forks=new JSpinner(new SpinnerNumberModel(3,1,10,1));
        JComboBox<String> guard=new JComboBox<>(new String[]{"三轴区间守卫","精确三轴守卫"});JPanel panel=new JPanel(new GridLayout(0,2,10,8));
        Object[][] entries={{"A行数 N",n},{"公共维 K",k},{"B列数 M",m},{"独立进程重复数",forks},{"分块内核守卫",guard}};for(Object[] e:entries){panel.add(new JLabel((String)e[0]));panel.add((Component)e[1]);}
        int option=JOptionPane.showOptionDialog(this,panel,"CPU矩阵乘法 · A[N,K] × B[K,M]",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,new Object[]{"生成固定种子输入","导入A和B两个CSV","取消"},"生成固定种子输入");if(option<0 || option>1)return;
        Path a=null,b=null;if(option==1){a=choose("选择矩阵A（无表头CSV）",false,null);if(a==null)return;b=choose("选择矩阵B（无表头CSV）",false,null);if(b==null)return;}
        Path inputA=a,inputB=b;var settings=new MatmulBenchmark.Settings((Integer)n.getValue(),(Integer)k.getValue(),(Integer)m.getValue(),(Integer)forks.getValue(),3,7,20261027,guard.getSelectedIndex()==1);
        task("正在比较参考、通用编译与分块矩阵内核…",()->MatmulBenchmark.launch(Path.of("results","matmul-ui-"+System.currentTimeMillis()),settings,inputA,inputB,this::live),report->{lastRun=null;lastReport=report;results.setText("CPU矩阵乘法实验已完成。\n\n三种执行策略各自使用独立进程，逐轮与独立参考实现核验。\n点击“查看报告”查看实际形状、冷请求、计算分位数、95%置信区间与系统噪声。\n\n报告："+report.toAbsolutePath());tabs.setSelectedIndex(4);status.setText("矩阵乘法校验与重复实验完成");});
    }
    private <T> void task(String text,Callable<T> work,java.util.function.Consumer<T> done) {
        progressBar.setIndeterminate(true);progressBar.setString(text);actions.forEach(b->b.setEnabled(false));controls.forEach(c->c.setEnabled(false));cancel.setEnabled(true);cancellationRequested=false;status.setForeground(TEAL);status.setText(text);
        new SwingWorker<T,Void>() {
            @Override protected T doInBackground() throws Exception {background=Thread.currentThread();try{if(cancellationRequested)throw new CancellationException("任务已取消");return work.call();}finally{background=null;}}
            @Override protected void done() {try{done.accept(get());progressBar.setIndeterminate(false);progressBar.setValue(1000);progressBar.setString("任务完成 · 结果已保存或显示");}catch(Exception ex){Throwable cause=ex.getCause()==null?ex:ex.getCause();if(cause instanceof CancellationException || cause instanceof InterruptedException){engine=null;progressBar.setString("任务已取消 · 保留实际完成记录");status.setText("任务已取消");foot.setText("已完成的工作进程记录仍保留在结果目录中。");}else {progressBar.setString("任务失败 · 请查看错误和日志");error(cause);}}finally{actions.forEach(b->b.setEnabled(true));controls.forEach(c->c.setEnabled(true));rows.setEnabled(importedMatrix==null);cols.setEnabled(importedMatrix==null);cancel.setEnabled(false);progressBar.setIndeterminate(false);browser.refresh();}}
        }.execute();
    }
    private EngineConfig currentConfig(){return profile.getSelectedIndex()==0?EngineConfig.defaults():profile.getSelectedIndex()==1?EngineConfig.demo():customConfig;}
    private Path choose(String title,boolean save,String name) {
        JFileChooser picker=new JFileChooser();picker.setDialogTitle(title);if(name!=null)picker.setSelectedFile(new java.io.File(name));
        int result=save?picker.showSaveDialog(this):picker.showOpenDialog(this);return result==JFileChooser.APPROVE_OPTION?picker.getSelectedFile().toPath():null;
    }
    private void openProject() {
        Path path=choose("打开算子工程",false,null);if(path==null)return;
        try {ProjectFile.Project p=ProjectFile.load(path);importedMatrix=null;importedTrace=null;workloads.removeItem("Custom CSV");reset();editor.setText(ZhCn.dsl(p.dsl()));strategies.setSelectedItem(p.strategy());customConfig=p.config();profile.setSelectedIndex(2);rows.setValue(p.rows());cols.setValue(p.cols());workloads.setSelectedItem(p.workload());currentProject=path;foot.setText("工程已打开："+path.getFileName());}catch(Exception e){error(e);}
    }
    private void saveProject() {
        Path path=currentProject!=null?currentProject:choose("保存算子工程",true,"project.sfxproj");if(path==null)return;
        try {String workload=(String)workloads.getSelectedItem();if("Custom CSV".equals(workload))workload="hotset";
            ProjectFile.save(path,new ProjectFile.Project(editor.getText(),(Strategy)strategies.getSelectedItem(),currentConfig(),(Integer)rows.getValue(),(Integer)cols.getValue(),workload));currentProject=path;foot.setText("工程已保存："+path.getFileName()+"；输入数据需单独导入");}catch(Exception e){error(e);}
    }
    private void importDsl() {
        Path path=choose("导入算子 (.sfx)",false,null);if(path==null)return;
        try {String dsl=Files.readString(path,StandardCharsets.UTF_8);Parser.parse(dsl);reset();editor.setText(ZhCn.dsl(dsl));currentProject=null;foot.setText("已导入："+path.getFileName());}catch(Exception e){error(e);}
    }
    private void importMatrix() {
        Path matrix=choose("导入无表头的数值矩阵（CSV）",false,null);if(matrix==null)return;
        Path bias=null;
        if(JOptionPane.showConfirmDialog(this,"是否同时导入一行偏置数据？选择“否”将使用全零偏置。","偏置数据",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION){bias=choose("导入偏置数据（CSV）",false,null);if(bias==null)return;}
        Path biasFile=bias;
        task("正在读取数值矩阵…",()->TensorCsv.read(matrix,biasFile),value->{reset();importedMatrix=value;rows.setValue(value.rows());cols.setValue(value.cols());foot.setText("已载入真实矩阵 "+value.shape()+"；“运行”使用此数据，“回放”使用形状轨迹");});
    }
    private void importTrace() {
        Path path=choose("导入形状轨迹（列名：rows,cols,repeat）",false,null);if(path==null)return;
        try {importedTrace=Workloads.fromCsv(path);boolean custom=false;for(int i=0;i<workloads.getItemCount();i++)custom|=workloads.getItemAt(i).equals("Custom CSV");if(!custom)workloads.addItem("Custom CSV");workloads.setSelectedItem("Custom CSV");reset();foot.setText("已导入形状轨迹："+importedTrace.size()+" 次请求");}catch(Exception e){error(e);}
    }
    private void settings() {
        EngineConfig c=currentConfig();JSpinner cache=new JSpinner(new SpinnerNumberModel(c.maxSpecializations(),1,128,1)),hot=new JSpinner(new SpinnerNumberModel(c.hotThreshold(),1,Integer.MAX_VALUE,1)),
                budget=new JSpinner(new SpinnerNumberModel(c.compileBudgetNanos()/1_000_000L,0L,Long.MAX_VALUE/1_000_000L,50L)),horizon=new JSpinner(new SpinnerNumberModel(c.horizonMultiplier(),1,Integer.MAX_VALUE,1)),
                window=new JSpinner(new SpinnerNumberModel(c.reuseWindow(),1,10000,1)),rowSpan=new JSpinner(new SpinnerNumberModel(c.rowBucket(),0,Integer.MAX_VALUE,1)),colSpan=new JSpinner(new SpinnerNumberModel(c.colBucket(),0,Integer.MAX_VALUE,1)),
                recheck=new JSpinner(new SpinnerNumberModel(c.recheckEvery(),0,Integer.MAX_VALUE,1)),rounds=new JSpinner(new SpinnerNumberModel(c.probeRounds(),3,31,2));
        JCheckBox optimize=new JCheckBox("启用计算图优化",c.optimizeIr()),unroll=new JCheckBox("启用列循环展开",c.unroll()),events=new JCheckBox("记录逐请求派发日志",c.traceEvents());
        JPanel panel=new JPanel(new GridLayout(0,2,10,8));Object[][] entries={{"专用内核缓存上限",cache},{"热点触发次数",hot},{"专用编译软预算（毫秒）",budget},{"预测请求预算",horizon},{"热度观测窗口",window},{"行区间跨度（0=二次幂）",rowSpan},{"列区间跨度（0=固定列宽）",colSpan},{"复核间隔（0 表示关闭）",recheck},{"收益探测轮数（奇数）",rounds}};
        for(Object[] entry:entries){panel.add(new JLabel((String)entry[0]));panel.add((Component)entry[1]);}panel.add(optimize);panel.add(unroll);panel.add(events);
        if(JOptionPane.showConfirmDialog(this,panel,"编译引擎参数",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)try {
            customConfig=new EngineConfig((Integer)cache.getValue(),(Integer)hot.getValue(),Math.multiplyExact(((Number)budget.getValue()).longValue(),1_000_000L),optimize.isSelected(),unroll.isSelected(),(Integer)horizon.getValue(),(Integer)recheck.getValue(),events.isSelected(),(Integer)rounds.getValue(),(Integer)window.getValue(),(Integer)rowSpan.getValue(),(Integer)colSpan.getValue());profile.setSelectedIndex(2);reset();
        }catch(Exception e){error(e);}
    }
    private void reset() {
        rows.setEnabled(importedMatrix==null);cols.setEnabled(importedMatrix==null);
        engine=null;sessionKey="";history.clear();lastRun=null;lastReport=null;exportCsv="";cacheModel.setRowCount(0);plot.setValues(history);
        count.setText("--");hits.setText("--");compiles.setText("--");latency.setText("--");status.setForeground(TEAL);status.setText("已就绪 · 会话已重置");
        ir.setText("运行算子后，可在此查看中文计算图与优化结果。");source.setText("编译完成后，可在此查看生成的 Java 源代码。");trace.setText("已就绪。");results.setText("当前没有运行记录。");
    }
    private void openReport() {
        if(lastReport==null){foot.setText("请先点击“对比执行策略”生成实验报告。");return;}
        try {if(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))Desktop.getDesktop().browse(lastReport.toUri());else foot.setText("请打开报告文件："+lastReport.toAbsolutePath());}catch(Exception e){error(e);}
    }
    private void error(Throwable error) { status.setForeground(new Color(0xffa4a4)); status.setText("输入或执行发生错误"); foot.setText(ZhCn.message(error.getMessage())); results.setText("操作未完成。\n\n"+ZhCn.message(error.getMessage())); }
    private JButton button(String text,boolean primary,Runnable action) {
        JButton b=new JButton(text); b.setFont(ChineseFonts.font(Font.BOLD,13)); b.setBackground(primary?TEAL:new Color(0x1d304b)); b.setForeground(primary?BG:TEXT); b.setFocusPainted(false); b.setBorder(new EmptyBorder(10,10,10,10));
        b.setAlignmentX(Component.LEFT_ALIGNMENT); b.setMaximumSize(new Dimension(Integer.MAX_VALUE,38)); b.addActionListener(e->action.run()); actions.add(b); return b;
    }
    private static JPanel vertical() { JPanel p=new JPanel(); p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS)); p.setOpaque(false); return p; }
    private static JLabel label(String text,Color color,int size,int weight) { JLabel l=new JLabel(text); l.setForeground(color); l.setFont(ChineseFonts.font(weight,Math.max(12,size))); l.setAlignmentX(Component.LEFT_ALIGNMENT); return l; }
    private static JLabel metricValue() { return label("--",TEXT,25,Font.BOLD); }
    private static JPanel card(String title,JLabel value) { JPanel p=vertical(); p.setOpaque(true); p.setBackground(PANEL); p.setBorder(new CompoundBorder(new LineBorder(BORDER),new EmptyBorder(15,15,12,15))); p.add(label(title,MUTED,10,Font.BOLD)); p.add(Box.createVerticalStrut(10)); p.add(value); return p; }
    private static JTextArea codeArea(String text) { JTextArea a=new JTextArea(text); a.setBackground(PANEL); a.setForeground(TEXT); a.setCaretColor(TEAL); a.setFont(ChineseFonts.font(Font.PLAIN,13)); a.setTabSize(2); a.setMargin(new Insets(14,14,14,14)); return a; }
    private static JScrollPane scroll(JTextArea area) { JScrollPane p=new JScrollPane(area); p.setBorder(new LineBorder(BORDER)); p.setAlignmentX(Component.LEFT_ALIGNMENT); p.getViewport().setBackground(PANEL); return p; }
    private static void style(JComboBox<?> box) { box.setBackground(new Color(0x1d304b)); box.setForeground(TEXT); box.setFont(ChineseFonts.font(Font.PLAIN,13)); box.setMaximumSize(new Dimension(Integer.MAX_VALUE,32)); box.setAlignmentX(Component.LEFT_ALIGNMENT); box.setRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){JLabel cell=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);cell.setText(ZhCn.name(value));cell.setFont(ChineseFonts.font(Font.PLAIN,13));return cell;}}); }
    private static final class Pipeline extends JPanel {
        Pipeline(){setBackground(PANEL);setBorder(new LineBorder(BORDER));}
        @Override protected void paintComponent(Graphics graphics) { super.paintComponent(graphics); Graphics2D g=(Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(ChineseFonts.font(Font.BOLD,12)); g.setColor(TEAL); g.drawString("02  /  编译执行流程",16,23);
            String[] names={"表达式解析","计算图优化","生成 Java 代码","字节码编译","形状守卫与缓存"}; int gap=14,w=(getWidth()-32-4*gap)/5;
            for(int i=0;i<5;i++){int x=16+i*(w+gap);g.setColor(new Color(0x20334f));g.fillRoundRect(x,42,w,40,10,10);g.setColor(i==4?TEAL:TEXT);int tw=g.getFontMetrics().stringWidth(names[i]);g.drawString(names[i],x+(w-tw)/2,66);if(i<4){g.setColor(MUTED);g.drawLine(x+w+3,62,x+w+gap-3,62);}}
            g.dispose(); }
    }
    private static final class Plot extends JPanel {
        private List<Double> values=List.of(); Plot(){setBackground(PANEL);setBorder(new LineBorder(BORDER));}
        void setValues(List<Double> input){values=List.copyOf(input.subList(Math.max(0,input.size()-64),input.size()));repaint();}
        @Override protected void paintComponent(Graphics graphics){super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setFont(ChineseFonts.font(Font.BOLD,12));g.setColor(TEAL);g.drawString("03  /  实测内核计算耗时",16,24);g.setColor(MUTED);g.setFont(ChineseFonts.font(Font.PLAIN,11));g.drawString("此图仅显示计算耗时；含编译和调优的总耗时请查看“运行结果”。",16,43);
            if(values.isEmpty()){g.drawString("运行或回放负载后显示真实耗时曲线。",22,100);g.dispose();return;}
            double max=Math.max(1,Collections.max(values));int left=55,top=60,width=getWidth()-75,height=getHeight()-87;
            for(int j=0;j<=2;j++){int y=top+j*height/2;g.setColor(BORDER);g.drawLine(left,y,left+width,y);g.setColor(MUTED);g.drawString(String.format(Locale.ROOT,"%.0f",max*(2-j)/2),10,y+4);}
            g.setColor(TEAL);g.setStroke(new BasicStroke(2));int px=0,py=0;
            for(int i=0;i<values.size();i++){int x=left+i*width/Math.max(1,values.size()-1),y=top+height-(int)(values.get(i)/max*height);if(i>0)g.drawLine(px,py,x,y);g.fillOval(x-2,y-2,4,4);px=x;py=y;}g.setColor(MUTED);g.drawString("微秒",12,56);g.drawString("请求顺序（最近 64 次）",left,getHeight()-9);g.dispose();}
    }
    public static void open() {
        SwingUtilities.invokeLater(()->{ JFrame frame=new JFrame("ShapeFlux-J｜动态算子编译优化系统（中文版）"); frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE); frame.setContentPane(new Dashboard()); frame.setSize(1440,1000); frame.setMinimumSize(new Dimension(1160,880)); frame.setLocationRelativeTo(null); frame.setVisible(true); });
    }
    /** Render real Swing components without a display for reproducible layout verification. */
    public static void snapshot(Path path) throws Exception {
        snapshot(path,0);
    }
    public static void snapshot(Path path,int selectedTab) throws Exception {
        if(selectedTab<0 || selectedTab>6)throw new IllegalArgumentException("标签序号应在 0～6 之间");
        Engine engine=new Engine(Parser.parse(Examples.CSE),Strategy.BUCKET,EngineConfig.defaults());List<TensorInput> inputs=Workloads.inputs(Workloads.create("hotset",true),20261027);List<Engine.Execution> runs=new ArrayList<>();for(TensorInput input:inputs)runs.add(engine.execute(input));
        SwingUtilities.invokeAndWait(()->{try{Dashboard view=new Dashboard();view.templates.setSelectedItem("CSE + constant folding");view.strategies.setSelectedItem(Strategy.BUCKET);view.apply(new Run(engine,runs,inputs,Examples.CSE));view.tabs.setSelectedIndex(selectedTab);view.setSize(1440,960);layout(view);BufferedImage img=new BufferedImage(1440,960,BufferedImage.TYPE_INT_RGB);Graphics2D g=img.createGraphics();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);view.printAll(g);g.dispose();if(path.toAbsolutePath().getParent()!=null)Files.createDirectories(path.toAbsolutePath().getParent());ImageIO.write(img,"png",path.toFile());}catch(Exception e){throw new IllegalStateException(e);}});
    }
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container container)layout(container);}
}
