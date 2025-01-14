package bguspl.set.ex;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class DealerTest {
    @Mock
    private Env env;
    @Mock
    private Util util;
    @Mock
    private UserInterface ui;
    @Mock
    private Logger logger;

    @Mock
    private Table table;

    @Mock
    private Player player1;

    @Mock
    private Player player2;

    private Dealer dealer;

    @BeforeEach
    void setUp() {
        Env env = new Env(logger, new Config(logger, "config.properties"), ui, util);
        dealer = new Dealer(env, table, new Player[]{player1, player2});

    }

    @Test
    void testShouldFinish() {

        //terminate true
        dealer.terminate = true;
        assertTrue(dealer.shouldFinish());
        //deck is empty
        dealer.deck.clear();
        assertTrue(dealer.shouldFinish());
    }

    @Test
    void testAddSetToTemp(){
        List<Integer> set = new LinkedList<>();
        set.add(1);
        set.add(2);
        set.add(3);
        dealer.addSetToTemp(0,set);
        assertEquals(set,dealer.temp[0]);
    }
}
