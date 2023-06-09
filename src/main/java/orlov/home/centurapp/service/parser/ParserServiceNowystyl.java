package orlov.home.centurapp.service.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Tag;
import org.jsoup.safety.Safelist;
import org.jsoup.safety.Whitelist;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import orlov.home.centurapp.dto.AttributeWrapper;
import orlov.home.centurapp.dto.ImageData;
import orlov.home.centurapp.dto.OpencartDto;
import orlov.home.centurapp.entity.app.*;
import orlov.home.centurapp.entity.opencart.*;
import orlov.home.centurapp.service.api.translate.TranslateService;
import orlov.home.centurapp.service.appservice.FileService;
import orlov.home.centurapp.service.appservice.ScraperDataUpdateService;
import orlov.home.centurapp.service.appservice.UpdateDataService;
import orlov.home.centurapp.service.daoservice.app.*;
import orlov.home.centurapp.service.daoservice.opencart.*;
import orlov.home.centurapp.util.AppConstant;
import orlov.home.centurapp.util.OCConstant;

import javax.lang.model.element.TypeElement;
import javax.swing.text.html.HTML;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ParserServiceNowystyl extends ParserServiceAbstract {

    private final String SUPPLIER_NAME = "nowystyl";
    private final String SUPPLIER_URL = "https://nowystyl.ua/";
    private final String DISPLAY_NAME = "39 - НОВИЙ СТИЛЬ";
    private final String MANUFACTURER_NAME = "NOWY STYL";
    private final String URL_PART_PAGE = "&p=";

    private final AppDaoService appDaoService;
    private final OpencartDaoService opencartDaoService;
    private final TranslateService translateService;

    private final UpdateDataService updateDataService;

    public ParserServiceNowystyl(AppDaoService appDaoService, OpencartDaoService opencartDaoService, ScraperDataUpdateService scraperDataUpdateService, TranslateService translateService, FileService fileService, UpdateDataService updateDataService) {
        super(appDaoService, opencartDaoService, scraperDataUpdateService, translateService, fileService);
        this.appDaoService = appDaoService;
        this.opencartDaoService = opencartDaoService;
        this.translateService = translateService;
        this.updateDataService = updateDataService;
    }


    @Override
    public void doProcess() {
        try {

            OrderProcessApp orderProcessApp = new OrderProcessApp();
            Timestamp start = new Timestamp(Calendar.getInstance().getTime().getTime());

            SupplierApp supplierApp = buildSupplierApp(SUPPLIER_NAME, DISPLAY_NAME, SUPPLIER_URL);

            List<CategoryOpencart> siteCategories = getSiteCategories(supplierApp);
            List<ProductOpencart> productsFromSite = getProductsInitDataByCategory(siteCategories, supplierApp);

            OpencartDto opencartInfo = getOpencartInfo(productsFromSite, supplierApp);
            checkPrice(opencartInfo, supplierApp);

            List<ProductOpencart> fullProductsData = getFullProductsData(opencartInfo.getNewProduct(), supplierApp);

            fullProductsData
                    .forEach(opencartDaoService::saveProductOpencart);
            //:TODO update price in function checkPrice
            /*
            if(!opencartInfo.getNewProduct().isEmpty()) {
                updateDataService.updatePrice(supplierApp.getSupplierAppId());
            }*/
            updateProductSupplierOpencartBySupplierApp(supplierApp);

            Timestamp end = new Timestamp(Calendar.getInstance().getTime().getTime());
            orderProcessApp = opencartInfo.getOrderProcessApp();
            orderProcessApp.setStartProcess(start);
            orderProcessApp.setEndProcess(end);
            appDaoService.saveOrderDataApp(orderProcessApp);

        } catch (Exception ex) {
            log.warn("Exception parsing nowystyle", ex);
        }
    }

    @Override
    public List<CategoryOpencart> getSiteCategories(SupplierApp supplierApp) {

        List<CategoryOpencart> supplierCategoryOpencartDB = supplierApp.getCategoryOpencartDB();
        Document doc = getWebDocument(supplierApp.getUrl(), new HashMap<>());

        if (Objects.nonNull(doc)) {
            List<CategoryOpencart> mainCategories = doc.select("a.ammenu-link.-main.-parent")
                    .stream()
                    .map(ec -> {

                        String url = ec.attr("href");
                        String title = ec.attr("title");
                        log.info("Main site category title: {}, url: {}", title, url);
                        CategoryOpencart categoryOpencart = new CategoryOpencart.Builder()
                                .withUrl(url)
                                .withParentCategory(supplierApp.getMainSupplierCategory())
                                .withParentId(supplierApp.getMainSupplierCategory().getCategoryId())
                                .withTop(false)
                                .withStatus(false)
                                .build();
                        CategoryDescriptionOpencart description = new CategoryDescriptionOpencart.Builder()
                                .withName(title)
                                .withDescription(supplierApp.getName())
                                .withLanguageId(OCConstant.UA_LANGUAGE_ID)
                                .build();
                        categoryOpencart.getDescriptions().add(description);
                        return categoryOpencart;
                    })
                    //:TODO next line uncommitted only debug
                    //.findFirst().stream()
                    .collect(Collectors.toList());
            log.info("Main category size: {}", mainCategories.size());


            List<CategoryOpencart> siteCategoryStructure = mainCategories
                    .stream()
                    .map(this::recursiveWalkSiteCategory)
                    .collect(Collectors.toList());

            List<CategoryOpencart> siteCategoryList = siteCategoryStructure
                    .stream()
                    .map(sc -> recursiveCollectListCategory(sc, supplierCategoryOpencartDB))
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
            siteCategoryList.add(supplierApp.getMainSupplierCategory());
            siteCategoryList.add(supplierApp.getGlobalSupplierCategory());

            siteCategoryList
                    .forEach(c -> log.info("Full Site category: {}, subCategorySize: {}", c.getDescriptions().get(0).getName(), c.getCategoriesOpencart().size()));

            return siteCategoryList;

        }
        return new ArrayList<>();

    }

    @Override
    public List<ProductOpencart> getProductsInitDataByCategory(List<CategoryOpencart> categoriesWithProduct, SupplierApp supplierApp) {
        List<ProductOpencart> productsCategory = new ArrayList<>();

        categoriesWithProduct
                .stream()

                .filter(c -> {
                    int categoryId = c.getCategoryId();
                    List<CategoryOpencart> childrenCategory = categoriesWithProduct.stream().filter(sub -> sub.getParentId() == categoryId).collect(Collectors.toList());
                    return childrenCategory.isEmpty();
                })
                .peek(c -> {

                    String name = c.getDescriptions().get(0).getName();
                    CategoryApp categoryApp = getCategoryApp(name, supplierApp);

                    String url = c.getUrl();
                    log.info("Get info by url: {}", url);
                    Document doc = getWebDocument(url, new HashMap<>());

                    List<CategoryOpencart> parentsCategories = getParentsCategories(c, categoriesWithProduct);
                    log.info("parentsCategories size: {}", parentsCategories.size());


                    if (Objects.nonNull(doc)) {
                        int numberPagesCategory = 1;
                        Elements pagesElements = doc.select("p#toolbar-amount span.toolbar-number");
                        if (pagesElements.size() >= 3) {
                            double numberProductsPerPage = Double.parseDouble(pagesElements.get(1).text());
                            double numberProductsCategory = Double.parseDouble(pagesElements.get(2).text());
                            numberPagesCategory = (int) Math.ceil(numberProductsCategory / numberProductsPerPage);
                        } else if (pagesElements.isEmpty()) {
                            numberPagesCategory = 0;
                        }
                        log.info("Number pages :{}", numberPagesCategory);
                        int countPage = 1;


                        while (countPage <= numberPagesCategory) {
                            try {
                                if (countPage != 1) {
                                    String newUrlPage = url.concat(URL_PART_PAGE).concat(String.valueOf(countPage));
                                    doc = getWebDocument(newUrlPage, new HashMap<>());
                                }

                                if (Objects.nonNull(doc)) {

                                    Elements elementsProduct = doc.select("div.column.main div.products.wrapper.grid.products-grid.layout_1 li.item.product.product-item");

                                    elementsProduct
                                            .stream()
                                            .peek(ep -> {
                                                String urlProduct = ep.select("a.product-item-link").attr("href");
                                                String urlImage = ep.select("img.product-image-photo").attr("src");
                                                String title = ep.select("a.product-item-link").text().trim();
                                                String sku = ep.select("form[data-role=tocart-form]").attr("data-product-sku");
//                                                sku = Jsoup.parse(sku).text();
                                                String supplierModel = ep.select("form[data-role=tocart-form] input[name=product]").attr("value");
                                                String model = generateModel(supplierModel, "0000");
                                                String price = ep.select("span[data-price-type=finalPrice] span.price").get(0).text().replaceAll(" ", "").replace("грн", "");
                                                BigDecimal priceNumberFree = new BigDecimal(price).setScale(4);

                                                log.info("Product url: {}", urlProduct);
                                                log.info("Product title: {}", title);
                                                log.info("Product sku: {}", sku);
                                                log.info("Product price: {}", priceNumberFree);

                                                ProductProfileApp productProfileApp = new ProductProfileApp.Builder()
                                                        .withSupplierId(supplierApp.getSupplierAppId())
                                                        .withSupplierApp(supplierApp)
                                                        .withCategoryId(categoryApp.getCategoryId())
                                                        .withCategoryApp(categoryApp)
                                                        .withUrl(urlProduct)
                                                        .withSku(sku)
                                                        .withTitle(title)
                                                        .withPrice(priceNumberFree)
                                                        .build();

                                                log.info("Product profile: {}", productProfileApp);

                                                ProductOpencart product = new ProductOpencart.Builder()
                                                        .withProductProfileApp(productProfileApp)
                                                        .withUrlProduct(urlProduct)
                                                        .withUrlImage(urlImage)
                                                        .withModel(model)
                                                        .withTitle(title)
                                                        .withSku(sku)
                                                        .withJan(supplierApp.getName())
                                                        .withPrice(priceNumberFree)
                                                        .withItuaOriginalPrice(priceNumberFree)
                                                        .build();

                                                product.setCategoriesOpencart(parentsCategories);


                                                ProductOpencart prodFromList = productsCategory
                                                        .stream()
                                                        .filter(ps -> ps.getUrlProduct().equals(product.getUrlProduct()))
                                                        .findFirst()
                                                        .orElse(null);

                                                if (Objects.nonNull(prodFromList)) {

                                                    List<CategoryOpencart> categoriesOpencart = prodFromList.getCategoriesOpencart();
                                                    List<CategoryOpencart> newCategoriesOpencart = product.getCategoriesOpencart();
                                                    newCategoriesOpencart
                                                            .forEach(nc -> {
                                                                if (!categoriesOpencart.contains(nc)) {
                                                                    categoriesOpencart.add(nc);
                                                                }
                                                            });

                                                } else {
                                                    productsCategory.add(product);
                                                }

                                            })
                                            .collect(Collectors.toList());

                                }
                            } catch (Exception e) {
                                log.warn("Problem iterate page", e);
                            } finally {
                                countPage++;
                            }

                        }

                    }
                })
                .collect(Collectors.toList());

        return productsCategory;
    }

    //    TODO test attribute value update
    public void updateAttributeValue() {
        SupplierApp supplierApp = buildSupplierApp(SUPPLIER_NAME, DISPLAY_NAME, SUPPLIER_URL);
        List<CategoryOpencart> siteCategories = getSiteCategories(supplierApp);
        List<ProductOpencart> productsFromSite = getProductsInitDataByCategory(siteCategories, supplierApp);
        List<ProductOpencart> fullProductsData = getFullProductsData(productsFromSite, supplierApp);
    }

    @Override
    public List<ProductOpencart> getFullProductsData(List<ProductOpencart> products, SupplierApp supplierApp) {
        AtomicInteger count = new AtomicInteger();
        List<ProductOpencart> fullProducts = products
                .stream()
                .peek(p -> {
                    String urlProduct = p.getUrlProduct();
                    Document webDocument = getWebDocument(urlProduct, new HashMap<>());
                    log.info("{}. Get data from product url: {}", count.addAndGet(1), urlProduct);

                    if (Objects.nonNull(webDocument)) {

                        try {

                            String name = webDocument.select("span[data-ui-id=page-title-wrapper]").text();

                            String description = getDescription(webDocument);
                            log.info("Description final: {}", description);
                            ManufacturerApp manufacturerApp = getManufacturerApp(MANUFACTURER_NAME, supplierApp);
                            ProductProfileApp firstProfileApp = p.getProductProfileApp();
                            firstProfileApp.setManufacturerId(manufacturerApp.getManufacturerId());
                            firstProfileApp.setManufacturerApp(manufacturerApp);
                            ProductProfileApp savedProductProfile = getProductProfile(firstProfileApp, supplierApp);

                            p.setProductProfileApp(savedProductProfile);
                            setManufacturer(p, supplierApp);
                            setPriceWithMarkup(p);

                            ProductDescriptionOpencart productDescriptionOpencart = new ProductDescriptionOpencart.Builder()
                                    .withDescription(description)
                                    .withName(name)
                                    .withLanguageId(OCConstant.UA_LANGUAGE_ID)
                                    .withMetaH1(name)
                                    .withMetaDescription(name.concat("  купляйте в інтернет-магазині CENTUR за Найнижчою Ціною. Швидка Доставка. Офіційна Гарантія від Виробника. Акції, Знижки, Розпродажі! ☎ 0 800 307 999"))
                                    .withMetaTitle("Купити ".concat(name).concat("  інтернет-магазин CENTUR. Прямі Поставки. Ціни від Виробника."))
                                    .withMetaKeyword("Купити ".concat(name))
                                    .build();
                            p.getProductsDescriptionOpencart().add(productDescriptionOpencart);


                            Elements tableAttr = webDocument.select("table#product-attribute-specs-table-left tr");

                            List<AttributeWrapper> attributes = tableAttr
                                    .stream()
                                    .map(row -> {
                                        String th = row.select("th").text();
                                        String td = row.select("td").text();
                                        log.info("");
                                        AttributeWrapper attributeWrapper = new AttributeWrapper(th, td, null);
                                        log.info("Begin attribute: {}", attributeWrapper);
                                        AttributeWrapper attribute = getAttribute(attributeWrapper, supplierApp, savedProductProfile);
                                        log.info("Finish attribute: {}", attribute);
                                        return attribute;
                                    })
                                    .collect(Collectors.toList());
                            p.getAttributesWrapper().addAll(attributes);


                            Elements imagesElement = webDocument.select("script:containsData(mage/gallery/gallery)");
                            if (imagesElement.size() > 0) {
                                String scriptGallery = imagesElement.toString();
                                String beginPart = "\"data\":[";
                                int begin = scriptGallery.indexOf(beginPart) + beginPart.length() - 1;
                                int finish = scriptGallery.indexOf("]", begin) + 1;
                                String jsonGallery = scriptGallery.substring(begin, finish);
                                ObjectMapper objectMapper = new ObjectMapper();

                                try {
                                    List<ImageData> imageData = Arrays.asList(objectMapper.readValue(jsonGallery, ImageData[].class));

                                    List<ImageOpencart> productImages = imageData
                                            .stream()
                                            .map(i -> {
                                                String fullUrl = i.getFull();

                                                String sku = p.getSku().replaceAll("\\W", "_");

                                                String imgName = sku.concat("_").concat(String.valueOf(i.getPosition())).concat(fullUrl.substring(fullUrl.lastIndexOf(".")));

                                                String dbImgPath = AppConstant.PART_DIR_OC_IMAGE.concat(DISPLAY_NAME.concat("/")).concat(imgName);
                                                downloadImage(fullUrl, dbImgPath);

                                                if (i.getIsMain()) {
                                                    p.setImage(dbImgPath);
                                                    return null;
                                                }

                                                ImageOpencart imageOpencart = new ImageOpencart.Builder()
                                                        .withImage(dbImgPath)
                                                        .withSortOrder(i.getPosition())
                                                        .build();
                                                return imageOpencart;
                                            })
                                            .filter(Objects::nonNull)
                                            .collect(Collectors.toList());

                                    p.getImagesOpencart().addAll(productImages);


                                } catch (Exception e) {
                                    log.warn("Problem get images", e);
                                }

                            }

                        } catch (Exception ex) {
                            log.warn("Bad parsing product data", ex);
                        }
                    } else {
                        p.setId(-1);
                    }

                })
                .filter(p -> p.getId() != -1)
                .filter(p -> !p.getSku().isEmpty())
                .collect(Collectors.toList());

        return fullProducts;
    }


    public String getDescription(Document doc) {
        Elements descElement = doc.select("div.column-description:has(div.full-description)");
        String description = descElement.size() > 0 ? cleanDescription(descElement.get(0)) : "";
        log.info("Description UA text: {}", description);
        return wrapToHtml(description);
    }


    @Override
    public CategoryOpencart recursiveWalkSiteCategory(CategoryOpencart category) {
        String url = category.getUrl();
        Document doc = getWebDocument(url, new HashMap<>());
        log.info("Get subcategories of category: {}", category.getDescriptions().get(0).getName());
        if (Objects.nonNull(doc)) {
            Element subCateElement = doc.select("div.filter-options-title:contains(Категорія)").parents().first();
            if (Objects.nonNull(subCateElement)) {
                log.info("sub category: {}", subCateElement.text());
                List<CategoryOpencart> subCategories = subCateElement.select("li.item")
                        .stream()
                        .map(el -> {
                            String subUrl = el.select("a").attr("href");
                            log.info("subUrl: {}", subUrl);
                            String subTitle = el.select("a span.item-label").text();
                            log.info("main title: {} sub title: {}", category.getDescriptions().get(0).getName(), subTitle);
                            CategoryOpencart subCategory = new CategoryOpencart.Builder()
                                    .withUrl(subUrl)
                                    .withTop(false)
                                    .withParentCategory(category)
                                    .withStatus(false)
                                    .build();
                            CategoryDescriptionOpencart description = new CategoryDescriptionOpencart.Builder()
                                    .withName(subTitle)
                                    .withDescription(category.getDescriptions().get(0).getDescription())
                                    .withLanguageId(OCConstant.UA_LANGUAGE_ID)
                                    .build();
                            subCategory.getDescriptions().add(description);

                            recursiveWalkSiteCategory(subCategory);

                            return subCategory;

                        })
                        .collect(Collectors.toList());
                category.getCategoriesOpencart().addAll(subCategories);
            }
        }
        return category;
    }


    @Override
    public ProductProfileApp getProductProfile(ProductProfileApp productProfileApp, SupplierApp supplierApp) {
        List<ProductProfileApp> productProfilesAppDB = supplierApp.getProductProfilesApp();

        boolean contains = productProfilesAppDB.contains(productProfileApp);

        if (contains) {
            productProfileApp = productProfilesAppDB.get(productProfilesAppDB.indexOf(productProfileApp));
        } else {

            int manufacturerId = productProfileApp.getManufacturerId();

            if (manufacturerId == 0) {
                ManufacturerApp manufacturerApp = getManufacturerApp(MANUFACTURER_NAME, supplierApp);
                productProfileApp.setManufacturerId(manufacturerApp.getManufacturerId());
                productProfileApp.setManufacturerApp(manufacturerApp);
            }

            productProfileApp = appDaoService.saveProductProfileApp(productProfileApp);

        }

        return productProfileApp;
    }


    public String generateModel(String supplierModel, String other) {
        String substring = DISPLAY_NAME.substring(0, DISPLAY_NAME.indexOf("-")).trim();
        String result;
        if (supplierModel.isEmpty()) {
            result = substring.concat("--").concat(other);
        } else {
            result = substring.concat("-").concat(supplierModel);
        }
        return result;
    }

    public void updateNewModel() {
        opencartDaoService.getAllProductOpencartBySupplierAppName(SUPPLIER_NAME)
                .forEach(p -> {
                    String model = p.getModel();
                    String newModel = model.replaceAll(" ", "");
                    p.setModel(newModel);
                    opencartDaoService.updateModel(p);
                });
    }

    public void updateModel() {
        SupplierApp supplierApp = buildSupplierApp(SUPPLIER_NAME, DISPLAY_NAME, SUPPLIER_URL);
        List<ProductProfileApp> profiles = appDaoService.getAllProductProfileAppBySupplierId(supplierApp.getSupplierAppId());
        profiles
                .forEach(p -> {
                    try {
                        String url = p.getUrl();
                        Document doc = getWebDocument(url, new HashMap<>());
                        if (Objects.nonNull(doc)) {
                            String supplierModel = doc.select("div.product-info-stock-sku").text().replaceAll("Код:", "").trim();
                            String model = generateModel(supplierModel, String.valueOf(p.getProductProfileId()));
                            ProductOpencart productOpencart = new ProductOpencart.Builder()
                                    .withModel(model)
                                    .withSku(p.getSku())
                                    .withJan(supplierApp.getName())
                                    .build();
                            opencartDaoService.updateModel(productOpencart);
                        }
                    } catch (Exception ex) {
                        log.warn("exception update.", ex);
                    }
                });
    }


}
