package io.shapeflux.ui;

import io.shapeflux.experiment.ExperimentIndex;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.event.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Searchable local experiment browser embedded in the Chinese workbench. */
@SuppressWarnings("serial")
public final class ExperimentBrowser extends JPanel {
    private Path root=Path.of("results");private List<ExperimentIndex.Entry> entries=List.of();
    private final DefaultTableModel model=new DefaultTableModel(new Object[]{"实验目录","类型","状态","开始时间（UTC）","JDK","已完成进程","报告"},0){@Override public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(model);private final JLabel label=new JLabel();private final JTextField query=new JTextField(22);private final TableRowSorter<DefaultTableModel> sorter=new TableRowSorter<>(model);
    public ExperimentBrowser() {
        super(new BorderLayout(8,8));setBackground(new Color(0x121e32));table.setRowHeight(30);table.setBackground(new Color(0x121e32));table.setForeground(new Color(0xeaf1ff));table.setGridColor(new Color(0x263750));table.setSelectionBackground(new Color(0x244663));table.setSelectionForeground(Color.WHITE);table.getTableHeader().setBackground(new Color(0x20334f));table.getTableHeader().setForeground(new Color(0xeaf1ff));table.setAutoCreateRowSorter(false);table.setRowSorter(sorter);table.setFont(ChineseFonts.font(Font.PLAIN,12));table.getTableHeader().setFont(ChineseFonts.font(Font.BOLD,12));
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.LEFT));tools.add(new JLabel("筛选："));tools.add(query);
        addButton(tools,"刷新",this::refresh);addButton(tools,"选择结果目录",this::choose);addButton(tools,"打开报告",this::openReport);addButton(tools,"打开文件夹",this::openFolder);
        add(tools,BorderLayout.NORTH);add(new JScrollPane(table),BorderLayout.CENTER);label.setForeground(new Color(0x92a6c4));add(label,BorderLayout.SOUTH);
        query.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});refresh();
    }
    private void addButton(JPanel p,String text,Runnable task){JButton b=new JButton(text);b.addActionListener(e->task.run());p.add(b);}
    private void filter(){String q=query.getText().strip();sorter.setRowFilter(q.isEmpty()?null:RowFilter.regexFilter("(?i)"+java.util.regex.Pattern.quote(q)));}
    public void refresh(){try{entries=ExperimentIndex.scan(root);model.setRowCount(0);for(var e:entries)model.addRow(new Object[]{e.name(),e.category(),state(e.state()),e.created(),e.javaVersion(),e.completed(),e.reportAvailable()?"可查看":"执行中或无报告"});label.setText(root.toAbsolutePath()+" ｜ "+entries.size()+" 个实验；请选择一行打开报告。");}catch(Exception e){label.setText("读取实验目录失败："+e.getMessage());}}
    private static String state(String s){return switch(s){case "COMPLETE"->"已完成";case "FAILED"->"失败";case "CANCELLED"->"已取消";case "RUNNING"->"执行中";default->s;};}
    private void choose(){JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){root=chooser.getSelectedFile().toPath();refresh();}}
    private ExperimentIndex.Entry selected(){int row=table.getSelectedRow();return row<0?null:entries.get(table.convertRowIndexToModel(row));}
    private void openReport(){var e=selected();if(e==null){label.setText("请先选择一个实验。");return;}if(!e.reportAvailable()){label.setText("该实验尚未生成报告，请查看状态和工作进程日志。");return;}try{Desktop.getDesktop().browse(e.directory().resolve("report.html").toAbsolutePath().toUri());}catch(Exception error){label.setText("请手动打开："+e.directory().resolve("report.html").toAbsolutePath());}}
    private void openFolder(){var e=selected();if(e==null)return;try{Desktop.getDesktop().open(e.directory().toFile());}catch(Exception error){label.setText("目录："+e.directory().toAbsolutePath());}}
}
