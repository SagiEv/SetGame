package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;

import static java.lang.Thread.currentThread;
import static java.lang.Thread.sleep;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    public volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    public volatile ConcurrentLinkedDeque<Integer> keysPushed;
    private List<Integer> set;
    public volatile int numOfTokenPlaced = 0;
    private volatile boolean[] tokensOnBoard;
    private boolean tryAgain = false;
    private boolean scored = false;
    private volatile boolean failed = false;
    public long PenaltyTime = 0;
    private final Dealer dealer;


    // private boolean tryAgain = false;
    public volatile boolean finished;


    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        keysPushed = new ConcurrentLinkedDeque<>();
        set = new LinkedList<>();
        tokensOnBoard = new boolean[env.config.tableSize];
        finished = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            if (PenaltyTime == 3000)
                penalty();
            else if (PenaltyTime == 1000) {
                point();
                resetTokens();
            }
            PenaltyTime = 0;
            //System.out.println(keysPushed.size() + " " + tryAgain + " " + numOfTokenPlaced + " " + set.size() + " " + failed);
            while ((tryAgain || set.size() < 3) && !keysPushed.isEmpty()) {
                tryAgain = false;
                //System.out.println(keysPushed.isEmpty() + " " + keysPushed.peek());
                int currSlot = keysPushed.poll();
                if (!table.SlotIsEmpty(currSlot)) {
                    //System.out.println("currSlot=" + currSlot + "|" + tokensOnBoard[currSlot] + "->" + !tokensOnBoard[currSlot]);
                    tokensOnBoard[currSlot] = !tokensOnBoard[currSlot];
                    //System.out.println(tokensOnBoard[currSlot]);

                    if (!tokensOnBoard[currSlot]) {
                        //System.out.println("remove token in slot:" + currSlot + "|player id:" + id);
                        set.remove((Object) currSlot);
                        table.removeToken(this.id, currSlot);
                        numOfTokenPlaced--;
                    } else if (set.size() < 3) {
                        //                     System.out.println("placing token in:" + currSlot + " " + tokensOnBoard[currSlot]);
                        set.add(currSlot);
                        table.placeToken(this, currSlot);
                        numOfTokenPlaced++;
                    }
                }
            }
            if (set.size() == 3 && !failed) {
                //adding the player to queue for the dealer to check the set offered
                dealer.addPlayerToQueue(id);
                dealer.addSetToTemp(id, set);
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                    }
                }

            }
        }
    }


    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {

                /*
                 * THIS IS A STUPID AI IMPLEMENTATION
                 */
                Random rand = new Random();
                int rand_slot = rand.nextInt(env.config.tableSize);
                keyPressed(rand_slot);

                /*
                 * THIS IS A SMART AI IMPLEMENTATION
                 */
//                List<Integer> temp = table.cardsOnTable;
//                List<int[]> setsOnTable = env.util.findSets(temp,1);
//                if(!setsOnTable.isEmpty()){
//                    int[] set = setsOnTable.get(0);
////                    System.out.println(set);
//                    for(int i = 0; i < set.length; i ++){
//                        if(set[i]!=-1 && table.cardToSlot[set[i]]!=null)
//                            keyPressed(table.cardToSlot[set[i]]);
//                    }
//                }

            }
            env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;

    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (keysPushed.size() < 3 && table.dealerPlaceCards)
            keysPushed.add(slot);
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        freezeForScore();
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        freezeForPenalty();
        failed = true;
        if (!human) {
            tryAgain = true;
            Random rand = new Random();
            if (set.size() > 0) {
                int toDelete = set.remove(rand.nextInt(set.size()));
                env.ui.removeToken(id, toDelete);
                numOfTokenPlaced--;
            }
            int rand_slot = rand.nextInt(env.config.tableSize);
            keyPressed(rand_slot);
            failed = false;
        } else {
            while (!tryAgain) {
                //might throw Exception!
                if (set.size() > 0) {
                    // System.out.println("the set size is: "+set.size());
                    for (Integer slot : set) {
                        // System.out.println("slot: "+slot+" | keysPushed.peek() = "+keysPushed.peek());
                        if (keysPushed.peek() == slot) {
                            tryAgain = true;
                            failed = false;
                            // System.out.println("found");
                            break;
                        }
                    }
                    if (!tryAgain && !keysPushed.isEmpty())
                        keysPushed.removeFirst();
                } else
                    tryAgain = true;
            }
        }

    }


    public int score() {
        return score;
    }

    public List<Integer> getPlayerSet() {
        return set;
    }

    public void resetTokens() {
        numOfTokenPlaced = 0;
        set.clear();
        finished = false;
    }

    public void freezeForScore() {
//        try {
//            scored = false;
//            env.ui.setFreeze(id, 1000);
//            keysPushed.clear();
//            sleep(1000);
//        } catch (InterruptedException e) {
//        }
//        env.ui.setFreeze(id, 0);
        long freeze = env.config.pointFreezeMillis;
        //if less than a second there's we do it once
        if(freeze<1000) {
            scored = false;
            env.ui.setFreeze(id, freeze);
            try {
                sleep(freeze);
            } catch (InterruptedException e) {
            }
            freeze=0;
        }
        while (freeze > 0) {
            scored = false;
            env.ui.setFreeze(id, freeze);
            try {
                sleep(1000);
            } catch (InterruptedException e) {
            }
            freeze = freeze - 1000;
        }
        keysPushed.clear();
//        needToWait = true;
        env.ui.setFreeze(id, 0);
    }

    public void freezeForPenalty() {
        long freeze = env.config.penaltyFreezeMillis;
        if(freeze<1000) {
            scored = false;
            env.ui.setFreeze(id, freeze);
            try {
                sleep(freeze);
            } catch (InterruptedException e) {
            }
            freeze=0;
        }
        while (freeze > 0) {
            env.ui.setFreeze(id, freeze);
            try {
                sleep(1000);
            } catch (InterruptedException e) {
            }
            freeze = freeze - 1000;
        }
        keysPushed.clear();
        env.ui.setFreeze(id, 0);
//        try {
//            env.ui.setFreeze(id, 3000);
//            sleep(1000);
//        } catch (InterruptedException e) {
//        }
//        try {
//            env.ui.setFreeze(id, 2000);
//            sleep(1000);
//        } catch (InterruptedException e) {
//        }
//        try {
//            env.ui.setFreeze(id, 1000);
//            sleep(1000);
//        } catch (InterruptedException e) {
//        }
//        try {
//            env.ui.setFreeze(id, 0);
//            sleep(1000);
//        } catch (InterruptedException e) {
//        }
    }
    public Thread getPlayerThread(){
        return playerThread;
    }
}
