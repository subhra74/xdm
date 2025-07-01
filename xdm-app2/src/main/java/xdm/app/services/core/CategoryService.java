package xdm.app.services.core;

import xdm.app.models.Category;

import java.util.List;

public class CategoryService {
  public static final CategoryService INSTANCE = new CategoryService();

  public List<Category> getAllCategories() {
    return List.of(
        Category.builder().displayText("Documents").value(0).build(),
        Category.builder().displayText("Music").value(0).build(),
        Category.builder().displayText("Video").value(0).build(),
        Category.builder().displayText("Compressed").value(0).build(),
        Category.builder().displayText("Apps").value(0).build(),
        Category.builder().displayText("Others").value(0).build());
  }
}
