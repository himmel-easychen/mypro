package com.agicore.rag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class PageTextCleanerTest {
 @Test void removesRepeatedHeaderWithoutDeletingUniqueContent(){
  var out=new PageTextCleaner().cleanPages(List.of("招标文件\n第1页\n甲", "招标文件\n第2页\n乙"));
  assertEquals("第1页\n甲",out.get(0));
 assertEquals("第2页\n乙",out.get(1));
 }

 @Test void removesLayoutNoiseAndJoinsBrokenTableWords(){
  var input = "[第 6 页]\n番禺区\n域特种\n车辆维\n保\n价,详\n见报价\n表\n否 / 否\n3.投标人资格要求";
  var out = new PageTextCleaner().cleanPages(List.of(input));
  assertFalse(out.get(0).contains("\n保"));
  assertFalse(out.get(0).contains("\n表"));
 }
}
