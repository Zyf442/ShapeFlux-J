package io.shapeflux;

import io.shapeflux.i18n.ZhCn;
import io.shapeflux.io.*;
import io.shapeflux.ir.*;
import io.shapeflux.runtime.*;
import io.shapeflux.ui.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Checks the localized presentation against canonical models and portable project files. */
final class ChineseSuite {
    private static int checks;
    private ChineseSuite() {}
    static int run() throws Exception {
        for(String dsl:Examples.all().values()) {
            Program original=Parser.parse(dsl),localized=Parser.parse(ZhCn.dsl(dsl));
            String before=original.fingerprint();String readable=ZhCn.ir(original);
            check(before.equals(localized.fingerprint()) && before.equals(original.fingerprint()),"Chinese comments/IR preserve program identity");
            check(readable.contains("最终输出") && ChineseFonts.supports(readable),"Chinese IR is readable with the selected font");
        }
        Path root=Files.createTempDirectory("shapeflux-chinese-");
        try {
            for(Strategy strategy:Strategy.values()) {
                ProjectFile.Project project=new ProjectFile.Project(ZhCn.dsl(Examples.ACTIVATION),strategy,EngineConfig.defaults(),3,7,"jitter");
                Path file=root.resolve("中文工程.sfxproj");ProjectFile.save(file,project);
                check(project.equals(ProjectFile.load(file)),"Chinese DSL round-trips with canonical strategy/workload IDs");
            }
            Program program=Parser.parse(Examples.CSE);Engine engine=new Engine(program,Strategy.GENERIC,EngineConfig.defaults());
            TensorInput input=TensorInput.random(3,7,42);Engine.Execution run=engine.execute(input);
            RunExporter.save(root.resolve("会话"),Examples.CSE,program,engine,List.of(input),List.of(run));
            String report=Files.readString(root.resolve("会话/运行报告.txt"));
            check(report.contains("数值校验通过") && report.contains("通用融合编译") && report.contains("编译完成"),"Export includes an actual Chinese numerical result and event report");
            check(Files.readString(root.resolve("会话/requests.csv")).contains("generic"),"Machine-readable export retains canonical kernel IDs");
        } finally {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        String fallback=ZhCn.event(new Engine.Event(1,"FALLBACK","Interpreter after generic compilation failure"));
        check(fallback.contains("改用解释执行") && !fallback.contains("Interpreter"),"Compiler fallback is explained in Chinese");
        check(ZhCn.message("Invalid number at line 3, column 2").equals("数值格式错误，行：3，列：2"),"CSV errors retain useful Chinese row/column positions");
        SwingUtilities.invokeAndWait(()->{
            Dashboard dashboard=new Dashboard();
            List<Component> components=new ArrayList<>();collect(dashboard,components);
            int buttons=0,combos=0,tabs=0;
            for(Component component:components) {
                if(component instanceof JButton button && !button.getText().isBlank()) {
                    check(han(button.getText()) && button.getFont().canDisplayUpTo(button.getText())==-1,"Buttons are Chinese and have glyph coverage");buttons++;
                }
                if(component instanceof JComboBox<?> combo) {
                    Object selected=combo.getSelectedItem();
                    for(int i=0;i<combo.getItemCount();i++) {
                        combo.setSelectedIndex(i);
                        // Invoke through the generic-safe helper with the real canonical model item.
                        String text=render(combo,combo.getItemAt(i));
                        check(han(text) && ChineseFonts.supports(text),"Every model option renders in Chinese");
                    }
                    combo.setSelectedItem(selected);combos++;
                }
                if(component instanceof JTabbedPane pane)for(int i=0;i<pane.getTabCount();i++){check(han(pane.getTitleAt(i)),"Result tabs are Chinese");tabs++;}
            }
            check(buttons>=21 && combos==4 && tabs==7,"All main controls were inspected");
            check("打开".equals(UIManager.getString("FileChooser.openButtonText")) && "取消".equals(UIManager.getString("OptionPane.cancelButtonText")),"Standard dialogs use Chinese actions");
        });
        return checks;
    }
    private static <T> String render(JComboBox<T> box,Object value) {
        @SuppressWarnings("unchecked") T item=(T)value;
        return ((JLabel)box.getRenderer().getListCellRendererComponent(new JList<T>(),item,-1,false,false)).getText();
    }
    private static void collect(Container root,List<Component> result){for(Component child:root.getComponents()){result.add(child);if(child instanceof Container container)collect(container,result);}}
    private static boolean han(String text){return text.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
