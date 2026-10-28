package io.shapeflux.ui;

import java.awt.*;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import javax.swing.plaf.FontUIResource;

/** Uses native Chinese fonts when available and the bundled unmodified Noto fallback otherwise. */
public final class ChineseFonts {
    private static final String SAMPLE="动态算子编译优化系统，打开工程，输入矩阵，参数设置，结果校验。";
    private static final Font BASE=load();
    private static boolean installed;
    private ChineseFonts() {}
    private static Font load() {
        Set<String> available=new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for(String name:new String[]{"Microsoft YaHei UI","Microsoft YaHei","PingFang SC","Noto Sans CJK SC","WenQuanYi Micro Hei","SimHei"}) {
            Font font=new Font(name,Font.PLAIN,14);if(available.contains(name) && font.canDisplayUpTo(SAMPLE)==-1)return font;
        }
        java.util.List<Path> roots=new ArrayList<>();roots.add(Path.of("").toAbsolutePath());
        try {Path p=Path.of(ChineseFonts.class.getProtectionDomain().getCodeSource().getLocation().toURI());if(Files.isRegularFile(p))p=p.getParent();
            for(int i=0;p!=null && i<4;i++,p=p.getParent())roots.add(p);
        }catch(Exception ignored){}
        for(Path root:roots) {Path path=root.resolve("assets/fonts/NotoSansCJKsc-Regular.otf");if(!Files.isRegularFile(path))continue;
            try(InputStream stream=Files.newInputStream(path)){Font font=Font.createFont(Font.TRUETYPE_FONT,stream);GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);if(font.canDisplayUpTo(SAMPLE)==-1)return font;}
            catch(Exception ignored){}
        }
        Font fallback=new Font(Font.DIALOG,Font.PLAIN,14);
        if(fallback.canDisplayUpTo(SAMPLE)==-1)return fallback;
        throw new IllegalStateException("未找到可用的中文字体，请保留项目中的 assets/fonts 目录。");
    }
    public static Font font(int style,float size){return BASE.deriveFont(style,size);}
    public static boolean supports(String text){return BASE.canDisplayUpTo(text)==-1;}
    public static synchronized void install() {
        if(installed)return;installed=true;Locale.setDefault(Locale.Category.DISPLAY,Locale.SIMPLIFIED_CHINESE);JComponent.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        FontUIResource font=new FontUIResource(font(Font.PLAIN,13));
        for(Object key:new ArrayList<>(UIManager.getDefaults().keySet()))if(key.toString().endsWith(".font"))UIManager.put(key,font);
        Map<String,String> words=Map.ofEntries(
            Map.entry("OptionPane.yesButtonText","是"),Map.entry("OptionPane.noButtonText","否"),Map.entry("OptionPane.cancelButtonText","取消"),Map.entry("OptionPane.okButtonText","确定"),
            Map.entry("FileChooser.openButtonText","打开"),Map.entry("FileChooser.saveButtonText","保存"),Map.entry("FileChooser.cancelButtonText","取消"),
            Map.entry("FileChooser.lookInLabelText","查找位置："),Map.entry("FileChooser.saveInLabelText","保存位置："),Map.entry("FileChooser.fileNameLabelText","文件名称："),
            Map.entry("FileChooser.filesOfTypeLabelText","文件类型："),Map.entry("FileChooser.acceptAllFileFilterText","所有文件"),Map.entry("FileChooser.openDialogTitleText","打开文件"),
            Map.entry("FileChooser.saveDialogTitleText","保存文件"),Map.entry("FileChooser.upFolderToolTipText","上一级目录"),Map.entry("FileChooser.homeFolderToolTipText","主目录"),
            Map.entry("FileChooser.newFolderToolTipText","新建文件夹"),Map.entry("FileChooser.listViewButtonToolTipText","列表视图"),Map.entry("FileChooser.detailsViewButtonToolTipText","详细信息"),
            Map.entry("FileChooser.fileNameHeaderText","名称"),Map.entry("FileChooser.fileSizeHeaderText","大小"),Map.entry("FileChooser.fileTypeHeaderText","类型"),Map.entry("FileChooser.fileDateHeaderText","修改时间")
        );words.forEach(UIManager::put);
    }
}
