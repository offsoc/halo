package run.halo.app.theme.finders.impl;

import static run.halo.app.extension.index.query.QueryFactory.notEqual;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.content.CategoryService;
import run.halo.app.core.extension.content.Category;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.theme.finders.CategoryFinder;
import run.halo.app.theme.finders.Finder;
import run.halo.app.theme.finders.vo.CategoryTreeVo;
import run.halo.app.theme.finders.vo.CategoryVo;

/**
 * A default implementation of {@link CategoryFinder}.
 *
 * @author guqing
 * @since 2.0.0
 */
@Slf4j
@Finder("categoryFinder")
@RequiredArgsConstructor
public class CategoryFinderImpl implements CategoryFinder {
    private final ReactiveExtensionClient client;
    private final CategoryService categoryService;

    @Override
    public Mono<CategoryVo> getByName(String name) {
        return client.fetch(Category.class, name)
            .map(CategoryVo::from);
    }

    @Override
    public Flux<CategoryVo> getByNames(List<String> names) {
        if (names == null) {
            return Flux.empty();
        }
        return Flux.fromIterable(names)
            .flatMap(this::getByName);
    }

    static Sort defaultSort() {
        return Sort.by(Sort.Order.desc("spec.priority"),
            Sort.Order.desc("metadata.creationTimestamp"),
            Sort.Order.desc("metadata.name"));
    }

    @Override
    public Mono<ListResult<CategoryVo>> list(Integer page, Integer size) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(
            notEqual("spec.hideFromList", BooleanUtils.TRUE)
        ));
        return client.listBy(Category.class, listOptions,
                PageRequestImpl.of(pageNullSafe(page), sizeNullSafe(size), defaultSort())
            )
            .map(list -> {
                List<CategoryVo> categoryVos = list.get()
                    .map(CategoryVo::from)
                    .collect(Collectors.toList());
                return new ListResult<>(list.getPage(), list.getSize(), list.getTotal(),
                    categoryVos);
            })
            .defaultIfEmpty(new ListResult<>(page, size, 0L, List.of()));
    }

    @Override
    public Flux<CategoryTreeVo> listAsTree() {
        return this.toCategoryTreeVoFlux(null);
    }

    @Override
    public Flux<CategoryTreeVo> listAsTree(String name) {
        return this.toCategoryTreeVoFlux(name);
    }

    @Override
    public Flux<CategoryVo> listAll() {
        return client.listAll(Category.class, new ListOptions(), defaultSort())
            .filter(category -> !category.getSpec().isHideFromList())
            .map(CategoryVo::from);
    }

    Flux<CategoryTreeVo> toCategoryTreeVoFlux(String name) {
        return listAll()
            .collectList()
            .flatMapIterable(categoryVos -> {
                Map<String, CategoryTreeVo> nameIdentityMap = categoryVos.stream()
                    .map(CategoryTreeVo::from)
                    .collect(Collectors.toMap(categoryVo -> categoryVo.getMetadata().getName(),
                        Function.identity()));

                nameIdentityMap.forEach((nameKey, value) -> {
                    List<String> children = value.getSpec().getChildren();
                    if (children == null) {
                        return;
                    }
                    for (String child : children) {
                        CategoryTreeVo childNode = nameIdentityMap.get(child);
                        if (childNode != null) {
                            childNode.setParentName(nameKey);
                        }
                    }
                });
                var tree = listToTree(nameIdentityMap.values(), name);
                recomputePostCount(tree);
                return tree;
            });
    }

    static List<CategoryTreeVo> listToTree(Collection<CategoryTreeVo> list, String name) {
        Map<String, List<CategoryTreeVo>> parentNameIdentityMap = list.stream()
            .filter(categoryTreeVo -> categoryTreeVo.getParentName() != null)
            .collect(Collectors.groupingBy(CategoryTreeVo::getParentName));

        list.forEach(node -> {
            // sort children
            List<CategoryTreeVo> children =
                parentNameIdentityMap.getOrDefault(node.getMetadata().getName(), List.of())
                    .stream()
                    .sorted(defaultTreeNodeComparator())
                    .toList();
            node.setChildren(children);
        });
        return list.stream()
            .filter(v -> StringUtils.isEmpty(name) ? v.getParentName() == null
                : StringUtils.equals(v.getMetadata().getName(), name))
            .sorted(defaultTreeNodeComparator())
            .collect(Collectors.toList());
    }

    private CategoryTreeVo dummyVirtualRoot(List<CategoryTreeVo> treeNodes) {
        Category.CategorySpec categorySpec = new Category.CategorySpec();
        categorySpec.setSlug("/");
        return CategoryTreeVo.builder()
            .spec(categorySpec)
            .postCount(0)
            .children(treeNodes)
            .metadata(new Metadata())
            .build();
    }

    void recomputePostCount(List<CategoryTreeVo> treeNodes) {
        var rootNode = dummyVirtualRoot(treeNodes);
        recomputePostCount(rootNode);
    }

    private int recomputePostCount(CategoryTreeVo rootNode) {
        if (rootNode == null) {
            return 0;
        }

        int originalPostCount = rootNode.getPostCount();

        for (var child : rootNode.getChildren()) {
            int childSum = recomputePostCount(child);
            if (!child.getSpec().isPreventParentPostCascadeQuery()) {
                rootNode.setPostCount(rootNode.getPostCount() + childSum);
            }
        }

        return rootNode.getSpec().isPreventParentPostCascadeQuery() ? originalPostCount
            : rootNode.getPostCount();
    }

    static Comparator<CategoryTreeVo> defaultTreeNodeComparator() {
        Function<CategoryTreeVo, Integer> priority =
            category -> Objects.requireNonNullElse(category.getSpec().getPriority(), 0);
        Function<CategoryTreeVo, Instant> creationTimestamp =
            category -> category.getMetadata().getCreationTimestamp();
        Function<CategoryTreeVo, String> name =
            category -> category.getMetadata().getName();
        return Comparator.comparing(priority)
            .thenComparing(creationTimestamp)
            .thenComparing(name);
    }

    static Comparator<Category> defaultComparator() {
        Function<Category, Integer> priority =
            category -> Objects.requireNonNullElse(category.getSpec().getPriority(), 0);
        Function<Category, Instant> creationTimestamp =
            category -> category.getMetadata().getCreationTimestamp();
        Function<Category, String> name =
            category -> category.getMetadata().getName();
        return Comparator.comparing(priority)
            .thenComparing(creationTimestamp)
            .thenComparing(name)
            .reversed();
    }

    @Override
    public Mono<CategoryVo> getParentByName(String name) {
        return categoryService.getParentByName(name)
            .map(CategoryVo::from);
    }

    int pageNullSafe(Integer page) {
        return ObjectUtils.defaultIfNull(page, 1);
    }

    int sizeNullSafe(Integer page) {
        return ObjectUtils.defaultIfNull(page, 10);
    }
}
