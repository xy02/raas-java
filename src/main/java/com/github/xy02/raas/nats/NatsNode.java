package com.github.xy02.raas.nats;

import com.github.xy02.nats.Connection;
import com.github.xy02.nats.MSG;
import com.github.xy02.nats.Options;
import com.github.xy02.raas.DataOuterClass.Data;
import com.github.xy02.raas.RaaSNode;
import com.github.xy02.raas.Service;
import com.github.xy02.raas.ServiceInfo;
import com.github.xy02.raas.Utils;
import com.google.protobuf.ByteString;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;

import java.io.IOException;

public class NatsNode implements RaaSNode {
    private static byte[] finalCompleteMessage = Data.newBuilder().setFinal("").build().toByteArray();

    private Connection nc;

    public NatsNode(Options options) throws IOException {
        nc = new Connection(options);
    }

    @Override
    public Observable<ServiceInfo> register(String serviceName, Service service) {
        Subject<ServiceInfo> serviceInfoSubject = PublishSubject.create();
        ServiceInfo info = new ServiceInfo();
        Disposable subD = nc.subscribeMsg(serviceName, "service")
                .flatMap(handshakeMsg -> onServiceConnected(service, handshakeMsg, serviceInfoSubject, info))
                .subscribe(x -> {
                }, serviceInfoSubject::onError);
        return serviceInfoSubject
                .doOnDispose(subD::dispose);
    }

    @Override
    public Observable<byte[]> call(String serviceName, Observable<byte[]> outputData) {
        return null;
    }

    @Override
    public Observable<byte[]> subscribe(String subejct) {
        return null;
    }

    @Override
    public Completable publish(String subejct, byte[] data) {
        return null;
    }

//
//    @Override
//    public Observable<byte[]> call(String serviceName, Observable<byte[]> outputData) {
//        return null;
//    }
//
//    @Override
//    public Observable<byte[]> subscribe(String subejct) {
//        return null;
//    }
//
//    @Override
//    public Completable publish(String subejct, byte[] data) {
//        return null;
//    }
//

    private Observable<byte[]> onServiceConnected(Service service, MSG handshakeMsg, Subject<ServiceInfo> serviceInfoSubject, ServiceInfo info) {
        String clientPort = new String(handshakeMsg.getBody());
        System.out.println("clientPort:" + clientPort);
        String servicePort = Utils.randomID();
        MSG replyMsg = new MSG(handshakeMsg.getReplyTo(), servicePort.getBytes());
        Subject<byte[]> inputData = PublishSubject.create();
        Disposable d = nc.subscribeMsg(servicePort)
                .map(MSG::getBody)
                .map(Data::parseFrom)
                .takeUntil(x -> x.getTypeCase().getNumber() == 2)
                .doOnNext(data -> {
                    int type = data.getTypeCase().getNumber();
                    if (type == 1)
                        inputData.onNext(data.getRaw().toByteArray());
                    if (type == 2) {
                        String err = data.getFinal();
                        if (err == null || err.isEmpty())
                            inputData.onComplete();
                        else
                            inputData.onError(new Exception(err));
                    }
                })
                .doOnDispose(inputData::onComplete)
                .subscribe();
        return service.onCall(new NatsContext(inputData))
                .doOnNext(raw -> outputNext(clientPort, raw))
                .doOnComplete(() -> outputComplete(clientPort))
                .doOnError(err -> outputError(clientPort, err))
                .doOnSubscribe(x -> {
                    nc.publish(replyMsg);
                    info.calledNum++;
                    serviceInfoSubject.onNext(info);
                })
                .doOnComplete(() -> {
                    info.completedNum++;
                    serviceInfoSubject.onNext(info);
                })
                .doOnError(err -> {
                    info.errorNum++;
                    serviceInfoSubject.onNext(info);
                })
                .doFinally(() -> System.out.println("call on final"))
                .doFinally(d::dispose);
    }

    private void outputNext(String clientPort, byte[] raw) {
        Data data = Data.newBuilder().setRaw(ByteString.copyFrom(raw)).build();
        nc.publish(new MSG(clientPort, data.toByteArray()));
    }

    private void outputComplete(String clientPort) {
        nc.publish(new MSG(clientPort, finalCompleteMessage));
    }

    private void outputError(String clientPort, Throwable t) {
        byte[] body = Data.newBuilder().setFinal(t.getMessage()).build().toByteArray();
        nc.publish(new MSG(clientPort, body));
    }

}