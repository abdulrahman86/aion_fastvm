/*
 * Copyright (c) 2017-2018 Aion foundation.
 *
 *     This file is part of the aion network project.
 *
 *     The aion network project is free software: you can redistribute it
 *     and/or modify it under the terms of the GNU General Public License
 *     as published by the Free Software Foundation, either version 3 of
 *     the License, or any later version.
 *
 *     The aion network project is distributed in the hope that it will
 *     be useful, but WITHOUT ANY WARRANTY; without even the implied
 *     warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *     See the GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with the aion network project source files.
 *     If not, see <https://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Aion foundation.
 */
package org.aion.vm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.aion.base.db.IRepositoryCache;
import org.aion.base.type.Address;
import org.aion.base.type.IExecutionResult;
import org.aion.base.type.ITxReceipt;
import org.aion.base.util.ByteUtil;
import org.aion.base.util.Hex;
import org.aion.contract.ContractUtils;
import org.aion.crypto.ECKeyFac;
import org.aion.fastvm.TestUtils;
import org.aion.fastvm.TestVMProvider;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.mcf.core.AccountState;
import org.aion.mcf.db.IBlockStoreBase;
import org.aion.mcf.vm.Constants;
import org.aion.mcf.vm.types.Bloom;
import org.aion.mcf.vm.types.DataWord;
import org.aion.mcf.vm.types.Log;
import org.aion.solidity.CompilationResult;
import org.aion.solidity.Compiler;
import org.aion.solidity.Compiler.Options;
import org.aion.vm.AbstractExecutionResult.ResultCode;
import org.aion.zero.impl.types.AionBlock;
import org.aion.zero.types.AionTransaction;
import org.aion.zero.types.AionTxExecSummary;
import org.aion.zero.types.AionTxReceipt;
import org.aion.zero.types.IAionBlock;
import org.apache.commons.lang3.RandomUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;

public class TransactionExecutorTest {
    private static final Logger LOGGER_VM = AionLoggerFactory.getLogger(LogEnum.VM.toString());
    private DummyRepository repo;

    @Before
    public void setup() {
        repo = new DummyRepository();
    }

    @After
    public void tearDown() {
        repo = null;
    }

    @Test
    public void testBuildReceiptEnergyUsedDataAllZeroes() {
        int size = RandomUtils.nextInt(0, 1000);
        checkBuildReceiptEnergyUsed(size, size, 0);
    }

    @Test
    public void testBuildReceiptEnergyUsedDataNoZeroes() {
        int size = RandomUtils.nextInt(0, 1000);
        checkBuildReceiptEnergyUsed(size, 0, size);
    }

    @Test
    public void testBuildReceiptEnergyDataSizeZero() {
        checkBuildReceiptEnergyUsed(0, 0, 0);
    }

    @Test
    public void testBuildReceiptEnergyRandomData() {
        int size = RandomUtils.nextInt(0, 1000);
        int numZeroes = RandomUtils.nextInt(0, size);
        checkBuildReceiptEnergyUsed(size, numZeroes, size - numZeroes);
    }

    @Test
    public void testBuildReceiptIsValidAndIsSuccessful() {
        // error is null or empty string <=> isValid is true
        // isValid == isSuccessful
        //    ^redundant, though technically isValid tests null but isSuccessful never sees null..
        AionTxReceipt receipt = produceReceipt(0, 0, false);
        receipt.setError(null);
        assertTrue(receipt.isValid());
        assertTrue(receipt.isSuccessful());
        receipt.setError("");
        assertTrue(receipt.isValid());
        assertTrue(receipt.isSuccessful());
        receipt.setError(" ");
        assertFalse(receipt.isValid());
        assertFalse(receipt.isSuccessful());
    }

    @Test
    public void testBuildReceiptGetTransaction() {
        int size = RandomUtils.nextInt(0, 1000);
        int numZeroes = RandomUtils.nextInt(0, size);
        byte[] data = produceData(size, numZeroes);
        AionTransaction tx = getNewAionTransaction(data, numZeroes, RandomUtils.nextLong(1, 10_000));
        tx.sign(ECKeyFac.inst().create());
        TransactionExecutor executor = getNewExecutor(tx, true, 3, numZeroes);
        AionTxReceipt receipt = (AionTxReceipt) executor.
            buildReceipt(new AionTxReceipt(), tx, getNewLogs(8));
        assertEquals(tx, receipt.getTransaction());
    }

    @Test
    public void testBuildReceiptBloomFilter() {
        int size = RandomUtils.nextInt(0, 1000);
        int numZeroes = RandomUtils.nextInt(0, size);
        byte[] data = produceData(size, numZeroes);
        List<Log> logs = getNewLogs(RandomUtils.nextInt(0, 50));
        AionTransaction tx = getNewAionTransaction(data, numZeroes, RandomUtils.nextLong(1, 10_000));
        TransactionExecutor executor = getNewExecutor(tx, false, 8, numZeroes);
        AionTxReceipt receipt = (AionTxReceipt) executor.buildReceipt(new AionTxReceipt(), tx, logs);
        assertEquals(logs.size(), receipt.getLogInfoList().size());
        assertEquals(getOrOfBlooms(logs), receipt.getBloomFilter());
    }

    @Test
    public void testBuildReceiptExecutionResult() {
        TransactionExecutor executor = getNewExecutor(mockTx(), false, 10, 0);
        byte[] output = RandomUtils.nextBytes(RandomUtils.nextInt(0, 1000));
        executor.setExecutionResult(new ExecutionResult(ResultCode.SUCCESS, 0, output));
        AionTxReceipt receipt = (AionTxReceipt) executor.buildReceipt(new AionTxReceipt(), mockTx(), new ArrayList());
        assertArrayEquals(output, receipt.getExecutionResult());
    }

    @Test
    public void testBuildReceiptGetErrorWhenResultIsSuccess() {
        TransactionExecutor executor = getNewExecutor(mockTx(), false, 10, 0);
        byte[] output = RandomUtils.nextBytes(RandomUtils.nextInt(0, 1000));
        executor.setExecutionResult(new ExecutionResult(ResultCode.SUCCESS, 0, output));
        AionTxReceipt receipt = (AionTxReceipt) executor.buildReceipt(new AionTxReceipt(), mockTx(), new ArrayList());
        assertEquals("", receipt.getError());
    }

    @Test
    public void testBuildReceiptGetErrorWhenResultNotSuccess() {
        ResultCode code = ResultCode.fromInt(RandomUtils.nextInt(0, 13) - 1);
        TransactionExecutor executor = getNewExecutor(mockTx(), false, 10, 0);
        byte[] output = RandomUtils.nextBytes(RandomUtils.nextInt(0, 1000));
        executor.setExecutionResult(new ExecutionResult(code, 0, output));
        AionTxReceipt receipt = (AionTxReceipt) executor.buildReceipt(new AionTxReceipt(), mockTx(), new ArrayList());
        assertEquals(code.name(), receipt.getError());
    }

    @Test
    public void testUpdateRepoIsLocalCall() {
        int size = RandomUtils.nextInt(0, 1000);
        int numZeroes = RandomUtils.nextInt(0, size);
        byte[] data = produceData(size, numZeroes);
        AionTransaction tx = getNewAionTransaction(data, numZeroes, RandomUtils.nextLong(1, 10_000));
        runUpdateRepo(tx, getNewAddress(), true, false, numZeroes);

        // When call is local there should be no state change.
        assertTrue(repo.accounts.isEmpty());
        assertTrue(repo.contracts.isEmpty());
        assertTrue(repo.storage.isEmpty());
    }

    @Test
    public void testUpdateRepoSummaryIsRejected() {
        int size = RandomUtils.nextInt(0, 1000);
        int numZeroes = RandomUtils.nextInt(0, size);
        byte[] data = produceData(size, numZeroes);
        AionTransaction tx = getNewAionTransaction(data, numZeroes, RandomUtils.nextLong(1, 10_000));
        runUpdateRepo(tx, getNewAddress(), false, true, numZeroes);

        // When summary is rejected there should be no state change.
        assertTrue(repo.accounts.isEmpty());
        assertTrue(repo.contracts.isEmpty());
        assertTrue(repo.storage.isEmpty());
    }

    @Test
    public void testUpdateRepoCoinbaseBalanceNotContractCreationTx() {
        int size = RandomUtils.nextInt(0, 1000);
        int numZeroes = RandomUtils.nextInt(0, size);
        byte[] data = produceData(size, numZeroes);
        Address coinbase = getNewAddress();
        AionTransaction tx = getNewAionTransaction(data, numZeroes, RandomUtils.nextLong(1, 10_000));
        runUpdateRepo(tx, coinbase, false, false, numZeroes);
        BigInteger coinbaseFee = computeCoinbaseFee(false, numZeroes,
            size - numZeroes, tx.getNrgPrice());
        assertEquals(coinbaseFee, repo.getBalance(coinbase));
    }

    @Test
    public void testUpdateRepoCoinbaseBalanceContractCreationTx() {
        int size = RandomUtils.nextInt(0, 1000);
        int numZeroes = RandomUtils.nextInt(0, size);
        byte[] data = produceData(size, numZeroes);
        Address coinbase = getNewAddress();
        AionTransaction tx = getNewAionTransactionContractCreation(data, numZeroes,
            RandomUtils.nextLong(1, 10_000));
        runUpdateRepo(tx, coinbase, false, false, numZeroes);
        BigInteger coinbaseFee = computeCoinbaseFee(true, numZeroes,
            size - numZeroes, tx.getNrgPrice());
        assertEquals(coinbaseFee, repo.getBalance(coinbase));
    }

    @Test
    public void testUpdateRepoCoinbaseTxHasZeroLengthData() {
        // First test contract creation tx.
        byte[] data = produceData(0, 0);
        Address coinbase = getNewAddress();
        AionTransaction tx = getNewAionTransactionContractCreation(data, 0,
            RandomUtils.nextLong(1, 10_000));
        runUpdateRepo(tx, coinbase, false, false, 0);
        BigInteger coinbaseFee = computeCoinbaseFee(true, 0,
            0, tx.getNrgPrice());
        assertEquals(coinbaseFee, repo.getBalance(coinbase));

        // Second test regular tx.
        coinbase = getNewAddress();
        tx = getNewAionTransaction(data, 0, RandomUtils.nextLong(1, 10_000));
        runUpdateRepo(tx, coinbase, false, false, 0);
        coinbaseFee = computeCoinbaseFee(false, 0, 0,
            tx.getNrgPrice());
        assertEquals(coinbaseFee, repo.getBalance(coinbase));
    }

    @Test
    public void testUpdateRepoCoinbaseDataAllZeroes() {
        // First test contract creation tx.
        int size = RandomUtils.nextInt(0, 1000);
        byte[] data = produceData(size, size);
        Address coinbase = getNewAddress();
        AionTransaction tx = getNewAionTransactionContractCreation(data, size,
            RandomUtils.nextLong(1, 10_000));
        runUpdateRepo(tx, coinbase, false, false, size);
        BigInteger coinbaseFee = computeCoinbaseFee(true, size, 0,
            tx.getNrgPrice());
        assertEquals(coinbaseFee, repo.getBalance(coinbase));

        // Second test regular tx.
        coinbase = getNewAddress();
        tx = getNewAionTransaction(data, size, RandomUtils.nextLong(1, 10_000));
        runUpdateRepo(tx, coinbase, false, false, size);
        coinbaseFee = computeCoinbaseFee(false, size, 0, tx.getNrgPrice());
        assertEquals(coinbaseFee, repo.getBalance(coinbase));
    }

    @Test
    public void testUpdateRepoCoinbaseDataNoZeroes() {
        // First test contract creation tx.
        int size = RandomUtils.nextInt(0, 1000);
        byte[] data = produceData(size, 0);
        Address coinbase = getNewAddress();
        AionTransaction tx = getNewAionTransactionContractCreation(data, 0,
            RandomUtils.nextLong(1, 10_000));
        runUpdateRepo(tx, coinbase, false, false, 0);
        BigInteger coinbaseFee = computeCoinbaseFee(true, 0, size,
            tx.getNrgPrice());
        assertEquals(coinbaseFee, repo.getBalance(coinbase));

        // Second test regular tx.
        coinbase = getNewAddress();
        tx = getNewAionTransaction(data, 0, RandomUtils.nextLong(1, 10_000));
        runUpdateRepo(tx, coinbase, false, false, 0);
        coinbaseFee = computeCoinbaseFee(false, 0, size, tx.getNrgPrice());
        assertEquals(coinbaseFee, repo.getBalance(coinbase));
    }

    @Test
    public void testUpdateRepoCoinbaseZeroNrgPrice() {
        // First test contract creation tx.
        int size = RandomUtils.nextInt(0, 1000);
        byte[] data = produceData(size, 0);
        Address coinbase = getNewAddress();
        AionTransaction tx = getNewAionTransactionContractCreation(data, 0, 0);
        runUpdateRepo(tx, coinbase, false, false, 0);
        assertEquals(BigInteger.ZERO, repo.getBalance(coinbase));

        // Second test regular tx.
        coinbase = getNewAddress();
        tx = getNewAionTransaction(data, 0, 0);
        runUpdateRepo(tx, coinbase, false, false, 0);
        assertEquals(BigInteger.ZERO, repo.getBalance(coinbase));
    }

    @Test
    public void testUpdateRepoNrgConsumptionContractCreationTx() {
        AionTransaction tx = mockTx();
        AionBlock block = mockBlock(getNewAddress());
        TransactionExecutor executor = new TransactionExecutor(tx, block, repo, false, LOGGER_VM);
        executor.updateRepo(produceSummary(executor, tx), tx, block.getCoinbase(), new ArrayList<>());
        assertEquals(tx.getNrgConsume(), computeEnergyConsumption(tx));
    }

    @Test
    public void testUpdateRepoEnergyPriceRefund() {
        for (ResultCode code : ResultCode.values()) {
            Address sender = getNewAddress();
            AionTransaction tx = mockTx(sender, RandomUtils.nextLong(0, 10_000));
            AionBlock block = mockBlock(getNewAddress());
            TransactionExecutor executor = new TransactionExecutor(tx, block, repo, false, LOGGER_VM);
            executor.setExecutionResult(new ExecutionResult(code, 0));

            AionTxExecSummary summary = produceSummary(executor, tx);
            executor.updateRepo(summary, tx, block.getCoinbase(), new ArrayList<>());

            // Refund occurs only when ResultCode is SUCCESS or REVERT.
            if (code.equals(ResultCode.SUCCESS) || code.equals(ResultCode.REVERT)) {
                assertEquals(computeRefund(tx, summary), repo.getBalance(sender));
            } else {
                assertEquals(BigInteger.ZERO, repo.getBalance(sender));
            }
        }
    }

    @Test
    public void testUpdateRepoDeletedAccounts() {
        for (ResultCode code : ResultCode.values()) {
            List<Address> accounts = addAccountsToRepo(RandomUtils.nextInt(5, 50));
            Address sender = getNewAddress();
            AionTransaction tx = mockTx(sender, RandomUtils.nextLong(0, 10_000));
            AionBlock block = mockBlock(getNewAddress());
            TransactionExecutor executor = new TransactionExecutor(tx, block, repo, false, LOGGER_VM);
            executor.setExecutionResult(new ExecutionResult(code, 0));

            AionTxExecSummary summary = produceSummary(executor, tx);
            executor.updateRepo(summary, tx, block.getCoinbase(), accounts);

            // Account deletion occurs only when ResultCode is SUCCESS.
            if (code.equals(ResultCode.SUCCESS)) {
                for (Address acc : repo.accounts.keySet()) {
                    assertFalse(accounts.contains(acc));
                }
            } else {
                Set<Address> repoAccounts = repo.accounts.keySet();
                for (Address acc : accounts) {
                    assertTrue(repoAccounts.contains(acc));
                }
            }
            repo.accounts.clear();
        }
    }

    @Test
    public void testGetNrgLeft() {
        AionTransaction tx = mockTx();
        AionBlock block = mockBlock(getNewAddress());
        TransactionExecutor executor = new TransactionExecutor(tx, block, repo, true, LOGGER_VM);
        assertEquals(tx.nrgLimit() - tx.transactionCost(0), executor.getNrgLeft());
    }

    @Test
    public void testConstructorExecutionContextForContractCreation() {
        boolean isContractCreation = true, valueIsNull = false, dataIsNull = false;
        AionTransaction tx = mockTx(isContractCreation, valueIsNull, dataIsNull);
        AionBlock block = mockBlock(DataWord.BYTES);
        TransactionExecutor executor = new TransactionExecutor(tx, block, repo, true,
            block.getNrgLimit(), LOGGER_VM);
        checkExecutionContext(executor, tx, block);
    }

    @Test
    public void testConstructorExecutionContextForRegTx() {
        boolean isContractCreation = false, valueIsNull = false, dataIsNull = false;
        AionTransaction tx = mockTx(isContractCreation, valueIsNull, dataIsNull);
        AionBlock block = mockBlock(DataWord.BYTES);
        TransactionExecutor executor = new TransactionExecutor(tx, block, repo, true,
            block.getNrgLimit(), LOGGER_VM);
        checkExecutionContext(executor, tx, block);
    }

    @Test
    public void testConstructorExecutionContextWithNullValue() {
        boolean isContractCreation = true, valueIsNull = true, dataIsNull = false;
        // isContractCreation == true
        AionTransaction tx = mockTx(isContractCreation, valueIsNull, dataIsNull);
        AionBlock block = mockBlock(DataWord.BYTES);
        TransactionExecutor executor = new TransactionExecutor(tx, block, repo, true,
            block.getNrgLimit(), LOGGER_VM);
        checkExecutionContext(executor, tx, block);

        // isContractCreation == false
        isContractCreation = false;
        tx = mockTx(isContractCreation, valueIsNull, dataIsNull);
        executor = new TransactionExecutor(tx, block, repo, true, block.getNrgLimit(), LOGGER_VM);
        checkExecutionContext(executor, tx, block);
    }

    @Test
    public void testConstructorExecutionContextWithNullData() {
        boolean isContractCreation = true, valueIsNull = false, dataIsNull = true;
        // isContractCreation == true
        AionTransaction tx = mockTx(isContractCreation, valueIsNull, dataIsNull);
        AionBlock block = mockBlock(DataWord.BYTES);
        TransactionExecutor executor = new TransactionExecutor(tx, block, repo, true,
            block.getNrgLimit(), LOGGER_VM);
        checkExecutionContext(executor, tx, block);

        // isContractCreation == false
        isContractCreation = false;
        tx = mockTx(isContractCreation, valueIsNull, dataIsNull);
        executor = new TransactionExecutor(tx, block, repo, true, block.getNrgLimit(), LOGGER_VM);
        checkExecutionContext(executor, tx, block);
    }

    @Test
    public void testConstructorExecutionContextLargeDifficulty() {
        boolean isContractCreation = true, valueIsNull = false, dataIsNull = false;
        // isContractCreation == true
        AionTransaction tx = mockTx(isContractCreation, valueIsNull, dataIsNull);
        AionBlock block = mockBlock(DataWord.BYTES * 5);
        TransactionExecutor executor = new TransactionExecutor(tx, block, repo, true,
            block.getNrgLimit(), LOGGER_VM);
        checkExecutionContext(executor, tx, block);

        // isContractCreation == false
        isContractCreation = false;
        tx = mockTx(isContractCreation, valueIsNull, dataIsNull);
        executor = new TransactionExecutor(tx, block, repo, true, block.getNrgLimit(), LOGGER_VM);
        checkExecutionContext(executor, tx, block);
    }

    @Test
    public void testConstructorUsesBlockNrgLimit() {
        // We are using a different constructor here. This one implicitly grabs the block's energy limit.

        boolean isContractCreation = true, valueIsNull = false, dataIsNull = false;
        // isContractCreation == true
        AionTransaction tx = mockTx(isContractCreation, valueIsNull, dataIsNull);
        AionBlock block = mockBlock(DataWord.BYTES);
        TransactionExecutor executor = new TransactionExecutor(tx, block, repo, true, LOGGER_VM);
        checkExecutionContext(executor, tx, block);

        // isContractCreation == false
        isContractCreation = false;
        tx = mockTx(isContractCreation, valueIsNull, dataIsNull);
        executor = new TransactionExecutor(tx, block, repo, true, LOGGER_VM);
        checkExecutionContext(executor, tx, block);
    }

    @Test
    public void testConstructorNonLocalUsesBlockNrgLimit() {
        // This is also a different constructor. This one grabs the block's energy limit also and
        // additionally it sets the isLocalCall variable false.

        boolean isContractCreation = true, valueIsNull = false, dataIsNull = false;
        // isContractCreation == true
        AionTransaction tx = mockTx(isContractCreation, valueIsNull, dataIsNull);
        AionBlock block = mockBlock(DataWord.BYTES);
        TransactionExecutor executor = new TransactionExecutor(tx, block, repo, LOGGER_VM);
        checkExecutionContext(executor, tx, block);

        // isContractCreation == false
        isContractCreation = false;
        tx = mockTx(isContractCreation, valueIsNull, dataIsNull);
        executor = new TransactionExecutor(tx, block, repo, LOGGER_VM);
        checkExecutionContext(executor, tx, block);
    }

    @Test
    public void testConstructorExecutionResult() {
        AionTransaction tx = mockTx();
        AionBlock block = mockBlock(DataWord.BYTES);
        TransactionExecutor executor = new TransactionExecutor(tx, block, repo, true,
            block.getNrgLimit(), LOGGER_VM);
        checkExecutionResult(executor, tx);

        // test second constructor.
        executor = new TransactionExecutor(tx, block, repo, true, LOGGER_VM);
        checkExecutionResult(executor, tx);

        // test third constructor.
        executor = new TransactionExecutor(tx, block, repo, LOGGER_VM);
        checkExecutionResult(executor, tx);
    }



    //                                  =================
    //                                   old tests below
    //                                  =================

    @Test
    public void testCallTransaction() throws IOException {
        Compiler.Result r = Compiler.getInstance().compile(
            ContractUtils.readContract("Ticker.sol"), Options.ABI, Options.BIN);
        CompilationResult cr = CompilationResult.parse(r.output);
        String deployer = cr.contracts.get("Ticker").bin; // deployer
        String contract = deployer.substring(deployer.indexOf("60506040", 1)); // contract

        byte[] txNonce = DataWord.ZERO.getData();
        Address from = Address
            .wrap(Hex.decode("1111111111111111111111111111111111111111111111111111111111111111"));
        Address to = Address
            .wrap(Hex.decode("2222222222222222222222222222222222222222222222222222222222222222"));
        byte[] value = DataWord.ZERO.getData();
        byte[] data = Hex.decode("c0004213");
        long nrg = new DataWord(100000L).longValue();
        long nrgPrice = DataWord.ONE.longValue();
        AionTransaction tx = new AionTransaction(txNonce, from, to, value, data, nrg, nrgPrice);

        AionBlock block = TestUtils.createDummyBlock();

        DummyRepository repo = new DummyRepository();
        repo.addBalance(from, BigInteger.valueOf(100_000).multiply(tx.nrgPrice().value()));
        repo.addContract(to, Hex.decode(contract));

        TransactionExecutor exec = new TransactionExecutor(tx, block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        AionTxReceipt receipt = exec.execute().getReceipt();
        System.out.println(receipt);

        assertArrayEquals(Hex.decode("00000000000000000000000000000000"),
            receipt.getExecutionResult());
    }

    @Test
    public void testCreateTransaction() throws IOException {
        Compiler.Result r = Compiler.getInstance().compile(
            ContractUtils.readContract("Ticker.sol"), Options.ABI, Options.BIN);
        CompilationResult cr = CompilationResult.parse(r.output);
        String deployer = cr.contracts.get("Ticker").bin;
        System.out.println(deployer);

        byte[] txNonce = DataWord.ZERO.getData();
        Address from = Address
            .wrap(Hex.decode("1111111111111111111111111111111111111111111111111111111111111111"));
        Address to = Address.EMPTY_ADDRESS();
        byte[] value = DataWord.ZERO.getData();
        byte[] data = Hex.decode(deployer);
        long nrg = 500_000L;
        long nrgPrice = 1;
        AionTransaction tx = new AionTransaction(txNonce, from, to, value, data, nrg, nrgPrice);

        AionBlock block = TestUtils.createDummyBlock();

        IRepositoryCache<AccountState, DataWord, IBlockStoreBase<?, ?>> repo = new DummyRepository();
        repo.addBalance(from, BigInteger.valueOf(500_000L).multiply(tx.nrgPrice().value()));

        TransactionExecutor exec = new TransactionExecutor(tx, block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        AionTxReceipt receipt = exec.execute().getReceipt();
        System.out.println(receipt);

        assertArrayEquals(Hex.decode(deployer.substring(deployer.indexOf("60506040", 1))),
            receipt.getExecutionResult());
    }

    @Test
    public void testPerformance() throws IOException {
        Compiler.Result r = Compiler.getInstance().compile(
            ContractUtils.readContract("Ticker.sol"), Options.ABI, Options.BIN);
        CompilationResult cr = CompilationResult.parse(r.output);
        String deployer = cr.contracts.get("Ticker").bin; // deployer
        String contract = deployer.substring(deployer.indexOf("60506040", 1)); // contract

        byte[] txNonce = DataWord.ZERO.getData();
        Address from = Address
            .wrap(Hex.decode("1111111111111111111111111111111111111111111111111111111111111111"));
        Address to = Address
            .wrap(Hex.decode("2222222222222222222222222222222222222222222222222222222222222222"));
        byte[] value = DataWord.ZERO.getData();
        byte[] data = Hex.decode("c0004213");
        long nrg = new DataWord(100000L).longValue();
        long nrgPrice = DataWord.ONE.longValue();
        AionTransaction tx = new AionTransaction(txNonce, from, to, value, data, nrg, nrgPrice);
        tx.sign(ECKeyFac.inst().create());

        AionBlock block = TestUtils.createDummyBlock();

        DummyRepository repo = new DummyRepository();
        repo.addBalance(from, BigInteger.valueOf(100_000).multiply(tx.nrgPrice().value()));
        repo.addContract(to, Hex.decode(contract));

        long t1 = System.nanoTime();
        long repeat = 1000;
        for (int i = 0; i < repeat; i++) {
            TransactionExecutor exec = new TransactionExecutor(tx, block, repo, LOGGER_VM);
            exec.setExecutorProvider(new TestVMProvider());
            exec.execute();
        }
        long t2 = System.nanoTime();
        System.out.println((t2 - t1) / repeat);
    }

    @Test
    public void testBasicTransactionCost() {
        byte[] txNonce = DataWord.ZERO.getData();
        Address from = Address
            .wrap(Hex.decode("1111111111111111111111111111111111111111111111111111111111111111"));
        Address to = Address
            .wrap(Hex.decode("2222222222222222222222222222222222222222222222222222222222222222"));
        byte[] value = DataWord.ONE.getData();
        byte[] data = new byte[0];
        long nrg = new DataWord(100000L).longValue();
        long nrgPrice = DataWord.ONE.longValue();
        AionTransaction tx = new AionTransaction(txNonce, from, to, value, data, nrg, nrgPrice);

        AionBlock block = TestUtils.createDummyBlock();

        DummyRepository repo = new DummyRepository();
        repo.addBalance(from, BigInteger.valueOf(1_000_000_000L));

        TransactionExecutor exec = new TransactionExecutor(tx, block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        AionTxReceipt receipt = exec.execute().getReceipt();
        System.out.println(receipt);

        assertEquals(tx.transactionCost(block.getNumber()), receipt.getEnergyUsed());
    }


    // <------------------------------------------HELPERS------------------------------------------>


    /**
     * Returns a new TransactionExecutor whose constructor params are randomly generated except for
     * isLocalCall. This executor executes tx and the tx data contains numZeroes zeroes and the block
     * containing the tx has energy limit blockNrg .
     *
     * @param tx The transaction.
     * @param isLocalCall True if a local call.
     * @param blockNrg The block energy limit.
     * @param numZeroes The number of zeroes in the data in tx.
     * @return a new TransactionExecutor.
     */
    private TransactionExecutor getNewExecutor(AionTransaction tx, boolean isLocalCall, long blockNrg,
        int numZeroes) {

        return getNewExecutor(tx, isLocalCall, blockNrg, numZeroes, getNewAddress());
    }

    /**
     * Returns a new TransactionExecutor whose constructor params are radomly generated except for
     * isLocalCall. This executor executes tx and the tx data contains numZeroes zeroes and the block
     * containing the tx has energy limit blockNrg and a coinbase coinbase.
     *
     * @param tx The transaction.
     * @param isLocalCall True if a local call.
     * @param blockNrg The block energy limit.
     * @param numZeroes The number of zeroes in the data in tx.
     * @param coinbase The coinbase.
     * @return a new TransactionExecutor.
     */
    private TransactionExecutor getNewExecutor(AionTransaction tx, boolean isLocalCall, long blockNrg,
        int numZeroes, Address coinbase) {

        IAionBlock block = getNewAionBlock(blockNrg, tx.getData(), numZeroes, coinbase);
        long nrgLeft = tx.transactionCost(block.getNumber());
        return new TransactionExecutor(tx, block, repo, isLocalCall, nrgLeft, LOGGER_VM);
    }

    /**
     * Returns a new AionBlock whose fields are randomized except for the ones provided by the input
     * parameters
     *
     * @param energyLimit The energy limit.
     * @param data The data.
     * @param numZeroes The number of zero bytes in data.
     * @param coinbase The block's coinbase account.
     * @return a new AionBlock.
     */
    private AionBlock getNewAionBlock(long energyLimit, byte[] data, int numZeroes, Address coinbase) {
        int arraySizes = RandomUtils.nextInt(0, 50);
        byte[] parentHash = RandomUtils.nextBytes(arraySizes);
        byte[] logsBloom = RandomUtils.nextBytes(arraySizes);
        byte[] difficulty = RandomUtils.nextBytes(arraySizes);
        long number = RandomUtils.nextLong(0, 10_000);
        long timestamp = RandomUtils.nextLong(0, 10_000);
        byte[] extraData = RandomUtils.nextBytes(arraySizes);
        byte[] nonce = RandomUtils.nextBytes(arraySizes);
        byte[] receiptsRoot = RandomUtils.nextBytes(arraySizes);
        byte[] transactionsRoot = RandomUtils.nextBytes(arraySizes);
        byte[] stateRoot = RandomUtils.nextBytes(arraySizes);
        List<AionTransaction> transactionList = getNewAionTransactions(3, data, numZeroes);
        byte[] solutions = RandomUtils.nextBytes(arraySizes);
        long energyConsumed = RandomUtils.nextLong(0, 10_000);
        return new AionBlock(parentHash, coinbase, logsBloom, difficulty, number, timestamp,
            extraData, nonce, receiptsRoot, transactionsRoot, stateRoot, transactionList,
            solutions, energyConsumed, energyLimit);
    }

    /**
     * Returns a list of num new AionTransactions whose fields are randomized.
     *
     * @param num The number of transactions in the list.
     * @param data The data to include in each of the transactions.
     * @param numZeroes The number of zero bytes in data.
     * @return the list of transactions.
     */
    private List<AionTransaction> getNewAionTransactions(int num, byte[] data, int numZeroes) {
        List<AionTransaction> transactions = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            transactions.add(getNewAionTransaction(data, numZeroes, RandomUtils.nextLong(1, 10_000)));
        }
        return transactions;
    }

    /**
     * Returns a list of num new random addresses.
     *
     * @param num The number of addresses to create.
     * @return the list of addresses.
     */
    private List<Address> getNewAddresses(int num) {
        List<Address> addresses = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            addresses.add(getNewAddress());
        }
        return addresses;
    }

    /**
     * Returns a new address consisting of random bytes.
     *
     * @return a new random address.
     */
    private Address getNewAddress() {
        return new Address(RandomUtils.nextBytes(Address.ADDRESS_LEN));
    }

    /**
     * Returns a collection of num new randomly generated logs.
     *
     * @param num The number of logs to produce.
     * @return the collection of new logs.
     */
    private List<Log> getNewLogs(int num) {
        List<Log> logs = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            logs.add(getNewLog());
        }
        return logs;
    }

    /**
     * Returns a newly created log consisting of a random number of topics of random bytes of random
     * size as well as a randomly sized random byte array of data.
     *
     * @return a new log.
     */
    private Log getNewLog() {
        int numTopics = RandomUtils.nextInt(0, 50);
        int topicSize = RandomUtils.nextInt(0, 100);
        int dataSize = RandomUtils.nextInt(0, 100);
        return new Log(getNewAddress(), generateTopics(numTopics, topicSize), RandomUtils.nextBytes(dataSize));
    }

    /**
     * Returns a list of num topics each of topicSize random bytes.
     *
     * @param num The number of topics to return.
     * @param topicSize The size of each topic.
     * @return the list of topics.
     */
    private List<byte[]> generateTopics(int num, int topicSize) {
        List<byte[]> topics = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            topics.add(RandomUtils.nextBytes(topicSize));
        }
        return topics;
    }

    /**
     * Returns a byte array of length size with numZeroes zero bytes.
     *
     * @param size The array size.
     * @param numZeroes The number of zeroes in the array.
     * @return the specified byte array.
     */
    private byte[] produceData(int size, int numZeroes) {
        byte[] data = new byte[size];
        for (int i = 0; i < (size - numZeroes); i++) {
            data[i] = 0x1;
        }
        return data;
    }

    /**
     * Checks the energy used field of a transaction receipt when build receipt is called. This
     * checks both contract creation and non-contract-creation logic. This method causes the calling
     * test to fail if there is an error.
     *
     * @param dataSize The data length.
     * @param numZeroes The number of zeroe-bytes in the data.
     * @param numNonZeroes The number of non-zero bytes in the data.
     */
    private void checkBuildReceiptEnergyUsed(int dataSize, int numZeroes, int numNonZeroes) {
        assertEquals(dataSize, numNonZeroes + numZeroes);

        // First check when we are not creating a contract.
        long energyUsed = computeTxCost(false, numZeroes, numNonZeroes);
        AionTxReceipt builtReceipt = produceReceipt(dataSize, numZeroes, false);
        assertEquals(energyUsed, builtReceipt.getEnergyUsed());

        // Second check when we are creating a contract.
        energyUsed = computeTxCost(true, numZeroes, numNonZeroes);
        builtReceipt = produceReceipt(dataSize, numZeroes, true);
        assertEquals(energyUsed, builtReceipt.getEnergyUsed());
    }

    /**
     * Returns a receipt for a transaction that is a contract creation if isContractCreation is
     * true and whose data is length dataSize and that data consists of numZeroes zero bytes.
     *
     * @param dataSize The transaction data length.
     * @param numZeroes The number of zero bytes in the data.
     * @param isContractCreation True only if transaction is for contract creation.
     * @return a new transaction receipt.
     */
    private AionTxReceipt produceReceipt(int dataSize, int numZeroes, boolean isContractCreation) {
        int numLogs = RandomUtils.nextInt(0, 50);
        return produceReceipt(dataSize, numZeroes, isContractCreation, getNewLogs(numLogs));
    }

    /**
     * Returns a receipt for a transaction that is a contract creation if isContractCreation is
     * true and whose data is length dataSize and that data consists of numZeroes zero bytes.
     *
     * @param dataSize The transaction data length.
     * @param numZeroes The number of zero bytes in the data.
     * @param isContractCreation True only if transaction is for contract creation.
     * @param logs The logs to add to the receipt.
     * @return a new transaction receipt.
     */
    private AionTxReceipt produceReceipt(int dataSize, int numZeroes, boolean isContractCreation,
        List<Log> logs) {

        byte[] data = produceData(dataSize, numZeroes);
        long nrgPrice = RandomUtils.nextLong(1, 10_000);
        AionTransaction tx = (isContractCreation) ?
            getNewAionTransactionContractCreation(data, numZeroes, nrgPrice) :
            getNewAionTransaction(data, numZeroes, nrgPrice);
        TransactionExecutor executor = getNewExecutor(tx,true, 0, numZeroes);
        ITxReceipt receipt = new AionTxReceipt();
        return (AionTxReceipt) executor.buildReceipt(receipt, tx, logs);
    }

    /**
     * Returns a new AionTransaction, most of whose fields are randomized. This transaction is not
     * for contract creation.
     *
     * @param data The transaction data.
     * @param numZeroes The number of zero bytes in data.
     * @param nrgPrice The price per unit of energy.
     * @return a new AionTransaction.
     */
    private AionTransaction getNewAionTransaction(byte[] data, int numZeroes, long nrgPrice) {
        int arraySizes = RandomUtils.nextInt(0, 50);
        byte[] nonce = RandomUtils.nextBytes(arraySizes);
        Address from = getNewAddress();
        Address to = getNewAddress();
        byte[] value = RandomUtils.nextBytes(DataWord.BYTES);
        return new AionTransaction(nonce, from, to, value, data, 10000000L, nrgPrice);
    }

    /**
     * Returns a new AionTransaction for contract creation logic. That is, its 'to' address is null.
     *
     * @param data The transaction data.
     * @param numZeroes The number of zero bytes in data.
     * @param nrgPrice The price per unit of energy.
     * @return a new AionTransaction for contract creation.
     */
    private AionTransaction getNewAionTransactionContractCreation(byte[] data, int numZeroes, long nrgPrice) {
        int arraySizes = RandomUtils.nextInt(0, 50);
        byte[] nonce = RandomUtils.nextBytes(arraySizes);
        Address from = getNewAddress();
        byte[] value = RandomUtils.nextBytes(DataWord.BYTES);
        return new AionTransaction(nonce, from, null, value, data, 10000000L, nrgPrice);
    }

    /**
     * Computes the transaction cost for processing a transaction whose data has numZeroes zero bytes
     * and numNonZeroes non-zero bytes.
     *
     * If transaction is a contract creation then the fee is:
     *   createFee + nrgTrans + (numZeroes * zeroDataNrg) + (numNonZeroes * nonzeroDataNrg)
     *
     * otherwise the fee is the same as above minus createFee.
     *
     * @param isContractCreation True if the transaction creates a new contract.
     * @param numZeroes The number of zero bytes in the transaction data.
     * @param numNonZeroes The umber of non-zero bytes in the transaction data.
     * @return the transaction cost.
     */
    private long computeTxCost(boolean isContractCreation, long numZeroes, long numNonZeroes) {
        return (isContractCreation ? Constants.NRG_TX_CREATE : 0)
            + Constants.NRG_TRANSACTION
            + (numZeroes * Constants.NRG_TX_DATA_ZERO)
            + (numNonZeroes * Constants.NRG_TX_DATA_NONZERO);
    }

    /**
     * Returns the logical-OR of all of the bloom filters contained in each log in logs as a bloom
     * filter itself.
     *
     * @param logs The logs.
     * @return a bloom filter that is the OR of all the filters in logs.
     */
    private Bloom getOrOfBlooms(List<Log> logs) {
        Bloom bloom = new Bloom();
        for (Log log : logs) {
            bloom.or(log.getBloom());
        }
        return bloom;
    }

    /**
     * Returns the fee that the coinbase receives for the newly mined block. This quantity is equal
     * to the amount of energy used by the transaction multiplied by the energy price.
     *
     * @param isContractCreation True if the transaction is for contract creation.
     * @param numZeroes The number of zero bytes in the transaction data.
     * @param numNonZeroes The number of non-zero bytes in the transaction data.
     * @param nrgPrice The energy price.
     * @return the coinbase's fee.
     */
    private BigInteger computeCoinbaseFee(boolean isContractCreation, int numZeroes, int numNonZeroes, long nrgPrice) {
        return BigInteger.valueOf(computeTxCost(isContractCreation, numZeroes,  numNonZeroes) * nrgPrice);
    }

    /**
     * Runs the updateRepo method of a TransactionExecutor that has been constructed according to
     * the specified parameters.
     *
     * @param tx The transaction.
     * @param coinbase The block coinbase.
     * @param isLocalCall True if call is local.
     * @param markRejected True if tx summary is to be marked as rejected.
     * @param numZeroes The number of zero bytes in the transaction data.
     */
    private void runUpdateRepo(AionTransaction tx, Address coinbase, boolean isLocalCall,
        boolean markRejected, int numZeroes) {

        byte[] result = RandomUtils.nextBytes(RandomUtils.nextInt(0, 50));
        List<Log> logs = getNewLogs(RandomUtils.nextInt(0, 20));
        TransactionExecutor executor = getNewExecutor(tx, isLocalCall, 21_000, numZeroes, coinbase);
        AionTxReceipt receipt = (AionTxReceipt) executor.buildReceipt(new AionTxReceipt(), tx, logs);
        AionTxExecSummary.Builder summaryBuilder = new AionTxExecSummary.Builder(receipt).result(result);
        if (markRejected) { summaryBuilder.markAsRejected(); }
        executor.updateRepo(summaryBuilder.build(), tx, coinbase, getNewAddresses(RandomUtils.nextInt(0, 10)));
    }

    /**
     * Returns the amount of energy consumed by the transaction tx.
     *
     * @param tx The transaction.
     * @return the amount of energy consumed.
     */
    private long computeEnergyConsumption(AionTransaction tx) {
        return tx.getNrg() - tx.nrgLimit() + tx.transactionCost(0);
    }

    /**
     * Produces a transaction summary using executor's build receipt from tx.
     *
     * @param executor The executor to build the receipt with.
     * @param tx The transaction which informs the receipt.
     * @return the transaction summary.
     */
    private AionTxExecSummary produceSummary(TransactionExecutor executor, AionTransaction tx) {
        return new AionTxExecSummary.
            Builder((AionTxReceipt) executor.buildReceipt(new AionTxReceipt(), tx, new ArrayList())).
            result(RandomUtils.nextBytes(RandomUtils.nextInt(0, 100))).
            build();
    }

    /**
     * Produces a mocked AionBlock whose difficulty consists of difficultyLength random bytes.
     *
     * @param difficultyLength The difficulty byte array length.
     * @return a mocked AionBlock.
     */
    private AionBlock mockBlock(int difficultyLength) {
        AionBlock block = mockBlock(getNewAddress());
        when(block.getDifficulty()).thenReturn(RandomUtils.nextBytes(difficultyLength));
        return block;
    }

    /**
     * Produces a mocked AionBlock whose getCoinbase method returns coinbase.
     *
     * @param coinbase The block's coinbase.
     * @return a mocked AionBlock.
     */
    private AionBlock mockBlock(Address coinbase) {
        AionBlock block = mock(AionBlock.class);
        when(block.getDifficulty()).thenReturn(RandomUtils.nextBytes(RandomUtils.nextInt(0, 100)));
        when(block.getCoinbase()).thenReturn(coinbase);
        when(block.getNumber()).thenReturn(RandomUtils.nextLong(0, 10_000));
        when(block.getTimestamp()).thenReturn(RandomUtils.nextLong(0, 10_000));
        when(block.getNrgLimit()).thenReturn(RandomUtils.nextLong(0, 10_000));
        return block;
    }

    /**
     * Produces a mocked AionTransaction with the following real methods:
     *   setNrgConsume
     *   getNrgConsume
     *
     * @return a mocked AionTransaction.
     */
    private AionTransaction mockTx() {
        return mockTx(getNewAddress(), RandomUtils.nextLong(0, 10_000));
    }

    /**
     * Produces a mocked AionTransaction with the following real methods:
     *   setNrgConsume
     *   getNrgConsume
     *
     * @param isContractCreation True only if transaction is for contract creation.
     * @param valueIsNull If true then tx.getValue() will return null.
     * @param dataIsNull If true then tx.getData() will return null.
     * @return a mocked AionTransaction.
     */
    private AionTransaction mockTx(boolean isContractCreation, boolean valueIsNull, boolean dataIsNull) {
        AionTransaction tx = mockTx(getNewAddress(), RandomUtils.nextLong(0, 10_000));
        when(tx.isContractCreation()).thenReturn(isContractCreation);
        if (valueIsNull) { when(tx.getValue()).thenReturn(null); }
        if (dataIsNull) { when(tx.getData()).thenReturn(null); }
        return tx;
    }

    /**
     * Produces a mocked AionTransaction with the following real methods:
     *   setNrgConsume
     *   getNrgConsume
     *
     * @param sender The sender of the transaction.
     * @param nrgPrice The energy price.
     * @return a mocked AionTransaction.
     */
    private AionTransaction mockTx(Address sender, long nrgPrice) {
        long txCost = RandomUtils.nextLong(2, 10_000);
        long nrgLimit = RandomUtils.nextLong(txCost, txCost + RandomUtils.nextLong(2, 10_000));
        long nrg = RandomUtils.nextLong(txCost, txCost + RandomUtils.nextLong(2, 10_000));
        AionTransaction tx = mock(AionTransaction.class);
        when(tx.getHash()).thenReturn(RandomUtils.nextBytes(32));
        when(tx.getData()).thenReturn(RandomUtils.nextBytes(RandomUtils.nextInt(0, 100)));
        when(tx.getNrg()).thenReturn(nrg);
        when(tx.nrgPrice()).thenReturn(new DataWord(RandomUtils.nextInt(0, 100)));
        when(tx.getNrgPrice()).thenReturn(nrgPrice);
        when(tx.nrgLimit()).thenReturn(nrgLimit);
        when(tx.getTo()).thenReturn(getNewAddress());
        when(tx.getContractAddress()).thenReturn(getNewAddress());
        when(tx.getFrom()).thenReturn(sender);
        when(tx.getValue()).thenReturn(BigInteger.valueOf(RandomUtils.nextInt(0, 100)).toByteArray());
        when(tx.transactionCost(Mockito.any(Long.class))).thenReturn(txCost);
        doCallRealMethod().when(tx).setNrgConsume(Mockito.any(Long.class));
        when(tx.getNrgConsume()).thenCallRealMethod();
        return tx;
    }

    /**
     * Returns the refund that the transaction sender is entitled to (if indeed entitled to a refund)
     * if the sender sends the transaction tx, from which summary is derived.
     *
     * @param tx The transaction.
     * @param summary The transaction summary.
     * @return the sender's refund.
     */
    private BigInteger computeRefund(AionTransaction tx, AionTxExecSummary summary) {
        return BigInteger.valueOf((tx.getNrg() - summary.getReceipt().getEnergyUsed()) * tx.getNrgPrice());
    }

    /**
     * Adds numAccounts to the repository and returns them in a list.
     *
     * @param numAccounts The number of accounts to add.
     * @return the list of newly added accounts.
     */
    private List<Address> addAccountsToRepo(int numAccounts) {
        List<Address> accounts = new ArrayList<>();
        for (int i = 0; i < numAccounts; i++) {
            Address acc = getNewAddress();
            repo.createAccount(acc);
            accounts.add(acc);
        }
        return accounts;
    }

    /**
     * Checks the fields of the internal ExecutionContext object that executor holds under the
     * assumption that executor was built from tx and block.
     *
     * @param tx The transaction.
     * @param block The block.
     */
    private void checkExecutionContext(TransactionExecutor executor, AionTransaction tx, AionBlock block) {
        ExecutionContext ctx = executor.getContext();
        Address recipient;
        int kind;
        byte[] data;
        if (tx.isContractCreation()) {
            recipient = tx.getContractAddress();
            kind = ExecutionContext.CREATE;
            data = ByteUtil.EMPTY_BYTE_ARRAY;
        } else {
            recipient = tx.getTo();
            kind = ExecutionContext.CALL;
            data = (tx.getData() == null) ? ByteUtil.EMPTY_BYTE_ARRAY : tx.getData();
        }

        byte[] value = (tx.getValue() == null) ? ByteUtil.EMPTY_BYTE_ARRAY : tx.getValue();
        byte[] tempDiff = block.getDifficulty();
        byte[] diff = (tempDiff.length > DataWord.BYTES) ?
            Arrays.copyOfRange(tempDiff, tempDiff.length - DataWord.BYTES, tempDiff.length) :
            tempDiff;

        assertArrayEquals(ctx.getTransactionHash(), tx.getHash());
        assertEquals(ctx.getRecipient(), recipient);
        assertEquals(ctx.getOrigin(), tx.getFrom());
        assertEquals(ctx.getCaller(), tx.getFrom());
        assertEquals(ctx.getNrgPrice(), tx.nrgPrice());
        assertEquals(ctx.getNrgLimit(), tx.nrgLimit() - tx.transactionCost(0));
        assertEquals(ctx.getCallValue(), new DataWord(value));
        assertArrayEquals(ctx.getCallData(), data);
        assertEquals(ctx.getDepth(), 0);
        assertEquals(ctx.getKind(), kind);
        assertEquals(ctx.getFlags(), 0);
        assertEquals(ctx.getBlockCoinbase(), block.getCoinbase());
        assertEquals(ctx.getBlockNumber(), block.getNumber());
        assertEquals(ctx.getBlockTimestamp(), block.getTimestamp());
        assertEquals(ctx.getBlockNrgLimit(), block.getNrgLimit());
        assertEquals(ctx.getBlockDifficulty(), new DataWord(diff));
    }

    /**
     * Checks that the internal IExecutionResult object that executor holds and the executor was
     * constructed using the transaction tx. If these checks fail then the calling test fails.
     *
     * @param tx The transaction that the TransactionExecutor was built off.
     */
    private void checkExecutionResult(TransactionExecutor executor, AionTransaction tx) {
        IExecutionResult result = executor.getResult();
        assertEquals(result.getCode(), ResultCode.SUCCESS.toInt());
        assertEquals(result.getNrgLeft(), tx.nrgLimit() - tx.transactionCost(0));
        assertArrayEquals(result.getOutput(), ByteUtil.EMPTY_BYTE_ARRAY);
    }

}
