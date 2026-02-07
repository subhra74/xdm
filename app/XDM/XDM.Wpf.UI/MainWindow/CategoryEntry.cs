using XDM.Core;

namespace XDM.Wpf.UI
{
    internal class CategoryWrapper
    {
        public readonly Category? category;
        public bool IsTopLevel { get; set; }
        public string? DisplayName { get; set; }
        public string VectorIcon { get; set; }
        public CategoryWrapper(Category category)
        {
            this.category = category;
        }
        public CategoryWrapper()
        {
        }
        public string? Name
            => category?.DisplayName ?? category?.Name ?? DisplayName;
    }
}
