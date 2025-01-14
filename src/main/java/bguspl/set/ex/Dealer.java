package bguspl.set.ex;

import bguspl.set.Env;

import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    public final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    public volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public ConcurrentLinkedQueue<Integer> PlayersSuggestedSet;
    public Object lock = new Object();
    public List<Integer>[] temp;


    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        PlayersSuggestedSet = new ConcurrentLinkedQueue<>();
        temp = new LinkedList[players.length];
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        for (Player p : players) {
            Thread t = new Thread(p);
//            t.setDaemon(true);
            t.start();
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            while (!table.ThereIsASetOnTable()) {
                removeAllCardsFromTable();
                placeCardsOnTable();
            }
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
            if (env.util.findSets(deck, 1).isEmpty())
                terminate = true;
        }
        announceWinners();
        terminate();
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
//            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for (int i = players.length-1; i >= 0; i--) {
            players[i].terminate();
            try {
                players[i].getPlayerThread().join();
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    public boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable(List<Integer> set) {

    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        Random rand = new Random();
        table.setPlaceOnTable(false);
        for (int i = 0; i < env.config.tableSize; i++) {
            if (table.countCards() < env.config.tableSize && deck.size() > 0) {
                int rand_ind = rand.nextInt(deck.size()); //choosing random index of deck cards
                int rand_card = deck.get(rand_ind);
                deck.remove(rand_ind);
                table.placeCard(rand_card, i);
            }
        }
        table.setPlaceOnTable(true);
    }


    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        while (reshuffleTime - System.currentTimeMillis() > 0) {
            if (getQueueSize() > 0) {
                // System.out.println(PlayersSuggestedSet.size());
                int currPlayerId = getFirstFromQueue();
                Player currPlayer = players[currPlayerId];
                // System.out.println(currPlayerId + " declared set");
                List<Integer> currSet = temp[currPlayerId];
                if (currSet.size() == 3) {
                    int[] set = new int[3];
                    int i = 0;
                    for (Integer slot : currSet) {
                        set[i] = table.getCardAtSlot(slot);
                        i++;
                    }
                    if (env.util.testSet(set)) {
                        currPlayer.PenaltyTime = 1000;
                        placeCardsOnSuccessfulSet(currPlayerId, currSet);
                    } else {
                            currPlayer.PenaltyTime = 3000;
                    }
                }
                synchronized (currPlayer) {
                    currPlayer.notifyAll();
                }

            }
            if (reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis)
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), true);
            else
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
        }


    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        } else if (reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis)
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), true);
        else
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
    }


    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        table.setPlaceOnTable(false);
        resetTokens();
        for (int i = 0; i < env.config.tableSize; i++) {
            if (!table.SlotIsEmpty(i)) {
                deck.add(table.getCardAtSlot(i));
                table.removeCard(i);
                //removes the tokens of all players on currSlot:
                while (!table.tokensOnBoard[i].isEmpty()) {
                    int tempPlayerId = table.tokensOnBoard[i].peek();
                    table.removeToken(tempPlayerId, i);
                    players[tempPlayerId].getPlayerSet().remove((Integer) i);
                }
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int temp;
        int maxScore = 0;
        for (int i = 0; i < players.length; i++) {
            temp = players[i].score();
            if (temp > maxScore)
                maxScore = temp;
        }
        List<Integer> Winners = new LinkedList<>();
        for (int i = 0; i < players.length; i++) {
            temp = players[i].score();
            if (temp == maxScore)
                Winners.add(players[i].id);
        }
        int[] WinnersArray = new int[Winners.size()];
        for (int i = 0; i < WinnersArray.length; i++) {
            WinnersArray[i] = Winners.get(i);
        }
        env.ui.announceWinner(WinnersArray);
    }

    private void placeCardsOnSuccessfulSet(int currPlayerId, List<Integer> setToDelete) {
        table.setPlaceOnTable(false);
        Random rand = new Random();
        while (!setToDelete.isEmpty()) {
            int slot = setToDelete.remove(0);
            while (!table.tokensOnBoard[slot].isEmpty()) {
                int temp = table.tokensOnBoard[slot].poll();
                table.removeToken(temp, slot);
                players[temp].getPlayerSet().remove((Integer) slot);
            }
            table.removeCard(slot);
            for (int i = 0; i < players.length; i++)
                if (table.countCards() < env.config.tableSize && deck.size() > 0) {
                    int rand_ind = rand.nextInt(deck.size());
                    int rand_card = deck.get(rand_ind);
                    deck.remove(rand_ind);
                    table.placeCard(rand_card, slot);
                }
        }
        table.setPlaceOnTable(true);


    }

    public void resetTokens() {
        for (Player p : players) {
            while (!p.getPlayerSet().isEmpty()) {
                int slotToDelete = p.getPlayerSet().remove(0);
                env.ui.removeToken(p.id, slotToDelete);
            }
            p.numOfTokenPlaced = 0;
            p.getPlayerSet().clear();
        }
    }

    public void addPlayerToQueue(int id) {
//        Deque<Integer> newList = new LinkedList<>(PlayersSuggestedSet.get());
//        do {
//            newList.add(id);
//        }
//        while(!PlayersSuggestedSet.compareAndSet(PlayersSuggestedSet.get(),newList));
        PlayersSuggestedSet.add(id);
    }

    public int getFirstFromQueue() {
//        int j = -1;
//        Deque<Integer> newList = new LinkedList<>(PlayersSuggestedSet.get());
//        do {
//             j = newList.poll();
//        }
//        while(!PlayersSuggestedSet.compareAndSet(PlayersSuggestedSet.get(),newList));
//        return j;
        return PlayersSuggestedSet.poll();
    }

    public int getQueueSize() {
        return PlayersSuggestedSet.size();
    }

    public void addSetToTemp(int id, List<Integer> set) {
        temp[id] = set;

    }
}
