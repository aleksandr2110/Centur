package orlov.home.centurapp.service.parser;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@Slf4j
class ParserServiceGoodfoodTest {

    @Autowired
    private ParserServiceGoodfood parserServiceGoodfood;

    @Test
    void getXmlProductData() {
        parserServiceGoodfood.getXmlProductData();
    }


    @Test
    void doProcess(){
        parserServiceGoodfood.doProcess();
    }

    @Test
    void translateSupplierProducts(){
        parserServiceGoodfood.translateSupplierProducts();
    }
}