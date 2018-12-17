package com.commercetools.sync.benchmark;


import com.commercetools.sync.benchmark.helpers.CtpObserver;
import com.commercetools.sync.products.ProductSync;
import com.commercetools.sync.products.ProductSyncOptions;
import com.commercetools.sync.products.ProductSyncOptionsBuilder;
import com.commercetools.sync.products.helpers.ProductSyncStatistics;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.client.metrics.SimpleMetricsSphereClient;
import io.sphere.sdk.models.Reference;
import io.sphere.sdk.products.ProductDraft;
import io.sphere.sdk.products.ProductDraftBuilder;
import io.sphere.sdk.products.ProductVariantDraft;
import io.sphere.sdk.products.ProductVariantDraftBuilder;
import io.sphere.sdk.products.commands.ProductCreateCommand;
import io.sphere.sdk.products.queries.ProductProjectionQuery;
import io.sphere.sdk.products.queries.ProductQuery;
import io.sphere.sdk.producttypes.ProductType;
import io.sphere.sdk.queries.PagedQueryResult;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.commercetools.sync.benchmark.BenchmarkUtils.CREATES_AND_UPDATES;
import static com.commercetools.sync.benchmark.BenchmarkUtils.CREATES_ONLY;
import static com.commercetools.sync.benchmark.BenchmarkUtils.NUMBER_OF_RESOURCE_UNDER_TEST;
import static com.commercetools.sync.benchmark.BenchmarkUtils.PRODUCT_SYNC;
import static com.commercetools.sync.benchmark.BenchmarkUtils.THRESHOLD;
import static com.commercetools.sync.benchmark.BenchmarkUtils.UPDATES_ONLY;
import static com.commercetools.sync.benchmark.BenchmarkUtils.saveNewResult;
import static com.commercetools.sync.commons.asserts.statistics.AssertionsForStatistics.assertThat;
import static com.commercetools.sync.integration.commons.utils.CategoryITUtils.OLD_CATEGORY_CUSTOM_TYPE_KEY;
import static com.commercetools.sync.integration.commons.utils.CategoryITUtils.OLD_CATEGORY_CUSTOM_TYPE_NAME;
import static com.commercetools.sync.integration.commons.utils.CategoryITUtils.createCategoriesCustomType;
import static com.commercetools.sync.integration.commons.utils.ProductITUtils.deleteAllProducts;
import static com.commercetools.sync.integration.commons.utils.ProductITUtils.deleteProductSyncTestData;
import static com.commercetools.sync.integration.commons.utils.ProductTypeITUtils.createProductType;
import static com.commercetools.sync.integration.commons.utils.SphereClientUtils.CTP_TARGET_CLIENT;
import static com.commercetools.sync.products.ProductSyncMockUtils.PRODUCT_TYPE_RESOURCE_PATH;
import static com.commercetools.tests.utils.CompletionStageUtil.executeBlocking;
import static io.sphere.sdk.models.LocalizedString.ofEnglish;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static org.assertj.core.api.Assertions.assertThat;

public class ProductSyncBenchmark {
    private static ProductType productType;
    private List<String> errorCallBackMessages;
    private List<String> warningCallBackMessages;
    private List<Throwable> errorCallBackExceptions;

    @BeforeClass
    public static void setup() {
        deleteProductSyncTestData(CTP_TARGET_CLIENT);
        createCategoriesCustomType(OLD_CATEGORY_CUSTOM_TYPE_KEY, ENGLISH,
            OLD_CATEGORY_CUSTOM_TYPE_NAME, CTP_TARGET_CLIENT);
        productType = createProductType(PRODUCT_TYPE_RESOURCE_PATH, CTP_TARGET_CLIENT);
    }

    @Before
    public void setupTest() {
        clearSyncTestCollections();
        deleteAllProducts(CTP_TARGET_CLIENT);
    }

    private void clearSyncTestCollections() {
        errorCallBackMessages = new ArrayList<>();
        errorCallBackExceptions = new ArrayList<>();
        warningCallBackMessages = new ArrayList<>();
    }

    private ProductSyncOptions buildSyncOptions(@Nonnull final SphereClient client) {
        final BiConsumer<String, Throwable> errorCallBack = (errorMessage, exception) -> {
            errorCallBackMessages.add(errorMessage);
            errorCallBackExceptions.add(exception);
        };
        final Consumer<String> warningCallBack = warningMessage -> warningCallBackMessages.add(warningMessage);

        return ProductSyncOptionsBuilder.of(client)
                                        .errorCallback(errorCallBack)
                                        .warningCallback(warningCallBack)
                                        .build();
    }

    @AfterClass
    public static void tearDown() {
        deleteProductSyncTestData(CTP_TARGET_CLIENT);
    }

    @Test
    public void sync_NewProducts_ShouldCreateProducts() throws IOException {

        final CtpObserver ctpObserver = CtpObserver.of();

        final SimpleMetricsSphereClient metricsSphereClient = SimpleMetricsSphereClient.of(CTP_TARGET_CLIENT);
        metricsSphereClient.getMetricObservable().addObserver(ctpObserver);


        final List<ProductDraft> productDrafts = buildProductDrafts(NUMBER_OF_RESOURCE_UNDER_TEST);

        // Sync drafts
        final ProductSync productSync = new ProductSync(buildSyncOptions(metricsSphereClient));

        final long beforeSyncTime = System.currentTimeMillis();
        final ProductSyncStatistics syncStatistics = executeBlocking(productSync.sync(productDrafts));
        final long totalTime = System.currentTimeMillis() - beforeSyncTime;

        final double diff = THRESHOLD - totalTime;
        assertThat(diff)
            .withFailMessage(format("Diff of benchmark '%e' is longer than expected threshold of '%d'.",
                diff, THRESHOLD))
            .isLessThanOrEqualTo(THRESHOLD);

        // Assert actual state of CTP project (total number of existing products)
        final CompletableFuture<Integer> totalNumberOfProducts =
            CTP_TARGET_CLIENT.execute(ProductProjectionQuery.ofStaged())
                             .thenApply(PagedQueryResult::getTotal)
                             .thenApply(Long::intValue)
                             .toCompletableFuture();
        executeBlocking(totalNumberOfProducts);
        assertThat(totalNumberOfProducts).isCompletedWithValue(NUMBER_OF_RESOURCE_UNDER_TEST);


        assertThat(syncStatistics).hasValues(NUMBER_OF_RESOURCE_UNDER_TEST, NUMBER_OF_RESOURCE_UNDER_TEST, 0, 0);
        assertThat(errorCallBackExceptions).isEmpty();
        assertThat(errorCallBackMessages).isEmpty();
        assertThat(warningCallBackMessages).isEmpty();

        saveNewResult(PRODUCT_SYNC, CREATES_ONLY, totalTime, ctpObserver);
    }

    @Test
    public void sync_ExistingProducts_ShouldUpdateProducts() throws IOException {
        final List<ProductDraft> productDrafts = buildProductDrafts(NUMBER_OF_RESOURCE_UNDER_TEST);
        // Create drafts to target project with different descriptions
        CompletableFuture.allOf(productDrafts.stream()
                                             .map(ProductDraftBuilder::of)
                                             .map(builder -> builder.description(
                                                 ofEnglish("oldDescription")))
                                             .map(builder -> builder.productType(productType.toReference()))
                                             .map(ProductDraftBuilder::build)
                                             .map(draft -> CTP_TARGET_CLIENT.execute(ProductCreateCommand.of(draft)))
                                             .map(CompletionStage::toCompletableFuture)
                                             .toArray(CompletableFuture[]::new))
                         .join();

        final CtpObserver ctpObserver = CtpObserver.of();
        final SimpleMetricsSphereClient metricsSphereClient = SimpleMetricsSphereClient.of(CTP_TARGET_CLIENT);
        metricsSphereClient.getMetricObservable().addObserver(ctpObserver);

        // Sync drafts
        final ProductSync productSync = new ProductSync(buildSyncOptions(metricsSphereClient));

        final long beforeSyncTime = System.currentTimeMillis();
        final ProductSyncStatistics syncStatistics = executeBlocking(productSync.sync(productDrafts));
        final long totalTime = System.currentTimeMillis() - beforeSyncTime;


        // Calculate time taken for benchmark and assert it lies within threshold
        final double diff = THRESHOLD - totalTime;
        assertThat(diff)
            .withFailMessage(format("Diff of benchmark '%e' is longer than expected threshold of '%d'.", diff,
                THRESHOLD))
            .isLessThanOrEqualTo(THRESHOLD);

        // Assert actual state of CTP project (number of updated products)
        final CompletableFuture<Integer> totalNumberOfUpdatedProducts =
            CTP_TARGET_CLIENT.execute(ProductQuery.of()
                                                  .withPredicates(p -> p.masterData().staged()
                                                                        .description().locale(ENGLISH)
                                                                        .is("newDescription")))
                             .thenApply(PagedQueryResult::getTotal)
                             .thenApply(Long::intValue)
                             .toCompletableFuture();

        executeBlocking(totalNumberOfUpdatedProducts);
        assertThat(totalNumberOfUpdatedProducts).isCompletedWithValue(NUMBER_OF_RESOURCE_UNDER_TEST);

        // Assert actual state of CTP project (total number of existing products)
        final CompletableFuture<Integer> totalNumberOfProducts =
            CTP_TARGET_CLIENT.execute(ProductProjectionQuery.ofStaged())
                             .thenApply(PagedQueryResult::getTotal)
                             .thenApply(Long::intValue)
                             .toCompletableFuture();
        executeBlocking(totalNumberOfProducts);
        assertThat(totalNumberOfProducts).isCompletedWithValue(NUMBER_OF_RESOURCE_UNDER_TEST);

        // Assert statistics
        assertThat(syncStatistics).hasValues(NUMBER_OF_RESOURCE_UNDER_TEST, 0, NUMBER_OF_RESOURCE_UNDER_TEST, 0);
        assertThat(errorCallBackExceptions).isEmpty();
        assertThat(errorCallBackMessages).isEmpty();
        assertThat(warningCallBackMessages).isEmpty();

        saveNewResult(PRODUCT_SYNC, UPDATES_ONLY, totalTime, ctpObserver);
    }

    @Test
    public void sync_WithSomeExistingProducts_ShouldSyncProducts() throws IOException {
        final List<ProductDraft> productDrafts = buildProductDrafts(NUMBER_OF_RESOURCE_UNDER_TEST);
        final int halfNumberOfDrafts = productDrafts.size() / 2;
        final List<ProductDraft> firstHalf = productDrafts.subList(0, halfNumberOfDrafts);

        // Create first half of drafts to target project with different description
        CompletableFuture.allOf(firstHalf.stream()
                                         .map(ProductDraftBuilder::of)
                                         .map(builder -> builder.description(
                                             ofEnglish("oldDescription")))
                                         .map(builder -> builder.productType(productType.toReference()))
                                         .map(ProductDraftBuilder::build)
                                         .map(draft -> CTP_TARGET_CLIENT.execute(ProductCreateCommand.of(draft)))
                                         .map(CompletionStage::toCompletableFuture)
                                         .toArray(CompletableFuture[]::new))
                         .join();

        final CtpObserver ctpObserver = CtpObserver.of();
        final SimpleMetricsSphereClient metricsSphereClient = SimpleMetricsSphereClient.of(CTP_TARGET_CLIENT);
        metricsSphereClient.getMetricObservable().addObserver(ctpObserver);
        // Sync new drafts
        final ProductSync productSync = new ProductSync(buildSyncOptions(metricsSphereClient));

        final long beforeSyncTime = System.currentTimeMillis();
        final ProductSyncStatistics syncStatistics = executeBlocking(productSync.sync(productDrafts));
        final long totalTime = System.currentTimeMillis() - beforeSyncTime;


        // Calculate time taken for benchmark and assert it lies within threshold
        final double diff = THRESHOLD - totalTime;
        assertThat(diff)
            .withFailMessage(format("Diff of benchmark '%e' is longer than expected threshold of '%d'.", diff,
                THRESHOLD))
            .isLessThanOrEqualTo(THRESHOLD);

        // Assert actual state of CTP project (number of updated products)
        final CompletableFuture<Integer> totalNumberOfUpdatedProducts =
            CTP_TARGET_CLIENT.execute(ProductQuery.of()
                                                  .withPredicates(p -> p.masterData().staged()
                                                                        .description().locale(ENGLISH)
                                                                        .is("oldDescription")))
                             .thenApply(PagedQueryResult::getTotal)
                             .thenApply(Long::intValue)
                             .toCompletableFuture();

        executeBlocking(totalNumberOfUpdatedProducts);
        assertThat(totalNumberOfUpdatedProducts).isCompletedWithValue(0);

        // Assert actual state of CTP project (total number of existing products)
        final CompletableFuture<Integer> totalNumberOfProducts =
            CTP_TARGET_CLIENT.execute(ProductQuery.of())
                             .thenApply(PagedQueryResult::getTotal)
                             .thenApply(Long::intValue)
                             .toCompletableFuture();
        executeBlocking(totalNumberOfProducts);
        assertThat(totalNumberOfProducts).isCompletedWithValue(NUMBER_OF_RESOURCE_UNDER_TEST);


        // Assert statistics
        assertThat(syncStatistics).hasValues(NUMBER_OF_RESOURCE_UNDER_TEST, halfNumberOfDrafts, halfNumberOfDrafts, 0);
        assertThat(errorCallBackExceptions).isEmpty();
        assertThat(errorCallBackMessages).isEmpty();
        assertThat(warningCallBackMessages).isEmpty();

        saveNewResult(PRODUCT_SYNC, CREATES_AND_UPDATES, totalTime, ctpObserver);
    }

    @Nonnull
    private List<ProductDraft> buildProductDrafts(final int numberOfProducts) {
        final List<ProductDraft> productDrafts = new ArrayList<>();
        final Reference<ProductType> draftsProductType = ProductType.referenceOfId(productType.getKey());
        for (int i = 0; i < numberOfProducts; i++) {
            final ProductVariantDraft masterVariantDraft = ProductVariantDraftBuilder.of()
                                                                                     .key("masterVariantKey_" + i)
                                                                                     .sku("sku_" + i)
                                                                                     .build();
            final ProductDraft productDraft = ProductDraftBuilder
                .of(draftsProductType, ofEnglish("name_" + i), ofEnglish("slug_" + i), masterVariantDraft)
                .description(ofEnglish("newDescription"))
                .key("productKey_" + i)
                .build();
            productDrafts.add(productDraft);
        }
        return productDrafts;
    }
}